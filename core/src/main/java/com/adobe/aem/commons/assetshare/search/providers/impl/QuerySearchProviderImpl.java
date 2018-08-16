/*
 * Asset Share Commons
 *
 * Copyright (C) 2017 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.adobe.aem.commons.assetshare.search.providers.impl;

import com.adobe.aem.commons.assetshare.components.predicates.PagePredicate;
import com.adobe.aem.commons.assetshare.search.QueryParameterPostProcessor;
import com.adobe.aem.commons.assetshare.search.SearchSafety;
import com.adobe.aem.commons.assetshare.search.UnsafeSearchException;
import com.adobe.aem.commons.assetshare.search.providers.QuerySearchPostProcessor;
import com.adobe.aem.commons.assetshare.search.providers.QuerySearchPreProcessor;
import com.adobe.aem.commons.assetshare.search.providers.SearchProvider;
import com.adobe.aem.commons.assetshare.search.results.AssetResult;
import com.adobe.aem.commons.assetshare.search.results.Result;
import com.adobe.aem.commons.assetshare.search.results.Results;
import com.adobe.aem.commons.assetshare.search.results.impl.results.QueryBuilderResultsImpl;
import com.adobe.aem.commons.assetshare.util.PredicateUtil;
import com.day.cq.dam.api.DamConstants;
import com.day.cq.search.*;
import com.day.cq.search.eval.PathPredicateEvaluator;
import com.day.cq.search.result.Hit;
import com.day.cq.search.result.SearchResult;
import com.day.text.Text;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.factory.ModelFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.util.*;

import static org.osgi.framework.Constants.SERVICE_RANKING;

@Component(property = {
        SERVICE_RANKING + ":Integer=" + Integer.MIN_VALUE
})
public class QuerySearchProviderImpl implements SearchProvider {
    private static final Logger log = LoggerFactory.getLogger(QuerySearchProviderImpl.class);

    @Reference
    private SearchSafety searchSafety;

    @Reference
    private QueryBuilder queryBuilder;

    @Reference
    private ModelFactory modelFactory;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private QuerySearchPreProcessor querySearchPreProcessor;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private QuerySearchPostProcessor querySearchPostProcessor;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private QueryParameterPostProcessor queryParametersPostProcessor;

    public boolean accepts(SlingHttpServletRequest request) {
        // This is the default with the lowest service ranking
        return true;
    }

    public Results getResults(final SlingHttpServletRequest request) throws UnsafeSearchException, RepositoryException {
        final ResourceResolver resourceResolver = request.getResourceResolver();
        final PredicateGroup root;

        if (querySearchPreProcessor != null) {
            root = querySearchPreProcessor.process(request, getParams(request));
        } else {
            root = PredicateGroup.create(getParams(request));
        }

        if (!searchSafety.isSafe(request.getResourceResolver(), root)) {
            throw new UnsafeSearchException("Search query will initiate an traversing query");
        }

        debugPreQuery(root);

        final Query query = queryBuilder.createQuery(root, resourceResolver.adaptTo(Session.class));
        final SearchResult searchResult = query.getResult();

        debugPostQuery(searchResult);

        final List<Result> results = new ArrayList<>();

        ResourceResolver resourceResolverLeakingReference = null;

        for (final Hit hit : searchResult.getHits()) {
            if (resourceResolverLeakingReference == null) {
                resourceResolverLeakingReference = hit.getResource().getResourceResolver();
            }

            try {
                final Resource hitResource = resourceResolver.getResource(hit.getPath());
                final AssetResult assetSearchResult = modelFactory.getModelFromWrappedRequest(request, hitResource, AssetResult.class);
                if (assetSearchResult != null) {
                    results.add(assetSearchResult);
                }
            } catch (RepositoryException e) {
                log.error("Could not retrieve search result", e);
            }
        }

        if (resourceResolverLeakingReference != null) {
            resourceResolverLeakingReference.close();
        }

        debugPostAdaptation(results);

        final QueryBuilderResultsImpl resultsImpl = new QueryBuilderResultsImpl(results, searchResult);

        if (querySearchPostProcessor != null) {
            return querySearchPostProcessor.process(request, query, resultsImpl, searchResult);
        } else {
            return resultsImpl;
        }
    }

    /**
     * Generates the QueryBuilder query params from the Page Predicate settings and the request attributes.
     *
     * @param request
     * @return the QueryBuilder parameter map.
     */
    private Map<String, String> getParams(final SlingHttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        // Copy over query params

        for (final Map.Entry<String, RequestParameter[]> entry : request.getRequestParameterMap().entrySet()) {
            params.put(entry.getKey(), entry.getValue()[0].getString());
        }

        // Remove common junk params
        cleanParams(params);

        final PagePredicate pagePredicate = request.adaptTo(PagePredicate.class);
        final PredicateGroup root = PredicateConverter.createPredicates(params);

        PagePredicate.ParamTypes[] excludeParamTypes = new PagePredicate.ParamTypes[]{};

        if (isPathsProvidedByRequestParams(pagePredicate, params)) {
            excludeParamTypes = new PagePredicate.ParamTypes[]{ PagePredicate.ParamTypes.PATH };
        }

        root.addAll(pagePredicate.getPredicateGroup(excludeParamTypes));

        // If not provided, use the defaults set on the Search Component resource
        addToPredicateGroupIfNotPresent(root, Predicate.ORDER_BY, pagePredicate.getOrderBy());
        addToPredicateGroupIfNotPresent(root, Predicate.ORDER_BY + "." + Predicate.PARAM_SORT, pagePredicate.getOrderBySort());

        params = PredicateConverter.createMap(root);
        if (queryParametersPostProcessor != null) {
            params = queryParametersPostProcessor.process(request, params);
        }

        return params;
    }

    private void addToPredicateGroupIfNotPresent(final PredicateGroup root, final String key, final String val) {
        if (root.getByName(key) == null) {
            root.add(PredicateConverter.createPredicates(ImmutableMap.<String, String>builder().
                    put(key, val).
                    build()));
        }
    }

    private boolean isPathsProvidedByRequestParams(final PagePredicate pagePredicate, final Map<String, String> requestParams) {
        final ValueMap pathPredicates = PredicateUtil.findPredicate(requestParams, PathPredicateEvaluator.PATH, PathPredicateEvaluator.PATH);

        if (pathPredicates.size() == 0) {
            return false;
        }

        final List<String> allowedPaths = pagePredicate.getPaths();
        final String[] allowedPathPrefixes = pagePredicate.getPaths().stream().map(path ->  StringUtils.removeEnd(path, "/") + "/").toArray(String[]::new);

        boolean hasAllowed = false;
        for (final String key : pathPredicates.keySet()) {
            final String path = Text.makeCanonicalPath(pathPredicates.get(key, String.class));

            if (StringUtils.startsWithAny(path, allowedPathPrefixes) || allowedPaths.contains(path)) {
                hasAllowed = true;
            } else {
                requestParams.remove(key);
            }
        }

        return hasAllowed;
    }

    private void cleanParams(Map<String, String> params) {
        params.remove("mode");
        params.remove("layout");
        params.remove("wcmmode");
        params.remove("forceeditcontext");
    }

    private void debugPreQuery(PredicateGroup predicateGroup) {
        if (log.isDebugEnabled()) {

            final Map<String, String> sortedParams = new TreeMap<>();
            sortedParams.putAll(PredicateConverter.createMap(predicateGroup));

            final StringBuilder sb = new StringBuilder();
            for(final Map.Entry<String, String> parameter : sortedParams.entrySet()) {
                sb.append("\n" + parameter.getKey() + " = " + parameter.getValue());
            }

            log.debug("Query Builder Parameters: {}", sb.toString());
        }
    }

    private void debugPostQuery(SearchResult searchResult) {
        if (log.isDebugEnabled()) {
            log.debug("Executed query statement:\n{}", searchResult.getQueryStatement());
            log.debug("Search results - Hits size [ {} ]", searchResult.getHits().size());
            log.debug("Search results - Page count [ {} ]", searchResult.getResultPages().size());
            log.debug("Search results - Page start index [ {} ]", searchResult.getStartIndex());
            log.debug("Search results - Running total [ {} ]", searchResult.getStartIndex() + searchResult.getHits().size());
            log.debug("Search results - Has more results [ {} ]", searchResult.hasMore());
            log.debug("Search results - Total matches [ {} ]", searchResult.getTotalMatches());
            log.debug("Search results - Execution time in ms [ {} ]", searchResult.getExecutionTimeMillis());
        }
    }

    private void debugPostAdaptation(List<Result> results) {
        if (log.isDebugEnabled()) {
            log.debug("Adapted [ {} ] results to Result models", results.size());
        }
    }
}