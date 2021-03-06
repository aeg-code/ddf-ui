/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package ddf.catalog.source.solr;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.joda.time.DateTime;
import org.locationtech.spatial4j.distance.DistanceUtils;
import org.opengis.filter.sort.SortBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.MultiPoint;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;
import com.vividsolutions.jts.io.WKTWriter;

import ddf.catalog.data.AttributeType.AttributeFormat;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.Result;
import ddf.catalog.filter.FilterDelegate;
import ddf.measure.Distance;
import ddf.measure.Distance.LinearUnit;

/**
 * Translates filter-proxy calls into Solr query syntax.
 */
public class SolrFilterDelegate extends FilterDelegate<SolrQuery> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolrFilterDelegate.class);

    public static final String XPATH_QUERY_PARSER_PREFIX = "{!xpath}";

    public static final String XPATH_FILTER_QUERY = "xpath";

    public static final String XPATH_FILTER_QUERY_INDEX = XPATH_FILTER_QUERY + "_index";

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    // *, ?, and / are escaped by the filter adapter
    private static final String[] LUCENE_SPECIAL_CHARACTERS =
            new String[] {"+", "-", "&&", "||", "!", "(", ")", "{", "}", "[", "]", "^", "\"", "~",
                    ":"};

    private static final String[] ESCAPED_LUCENE_SPECIAL_CHARACTERS =
            new String[] {"\\+", "\\-", "\\&&", "\\||", "\\!", "\\(", "\\)", "\\{", "\\}", "\\[",
                    "\\]", "\\^", "\\\"", "\\~", "\\:"};

    private static final String INTERSECTS_OPERATION = "Intersects";

    private static final double NEAREST_NEIGHBOR_DISTANCE_LIMIT = metersToDegrees(new Distance(1000,
            LinearUnit.NAUTICAL_MILE).getAs(LinearUnit.METER));

    // Using quantization of 12 to reduce error below 1%
    private static final int QUADRANT_SEGMENTS = 12;

    private static final WKTWriter WKT_WRITER = new WKTWriter();

    private static final String END_PAREN = " ) ";

    private static final String START_PAREN = " ( ";

    private static final String OR = " OR ";

    private static final String AND = " AND ";

    private static final String TO = " TO ";

    private static final String QUOTE = "\"";

    private static final String FILTER_QUERY_PARAM_NAME = "fq";

    private static final String SOLR_WILDCARD_CHAR = "*";

    private static final String SOLR_SINGLE_WILDCARD_CHAR = "?";

    private static final String SOLR_INCLUSIVE_START = ":[ ";

    private static final String SOLR_EXCLUSIVE_START = ":{ ";

    private static final String SOLR_INCLUSIVE_END = " ] ";

    private static final String SOLR_EXCLUSIVE_END = " } ";

    private static final double DEFAULT_ERROR_IN_METERS = 1;

    private static final double DEFAULT_ERROR_IN_DEGREES = metersToDegrees(DEFAULT_ERROR_IN_METERS);

    private static TimeZone utcTimeZone = TimeZone.getTimeZone("UTC");

    private DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private DynamicSchemaResolver resolver;

    private SortBy sortBy;

    private boolean isSortedByDistance = false;

    private String sortedDistancePoint;

    public SolrFilterDelegate(DynamicSchemaResolver resolver) {
        this.resolver = resolver;
        dateFormat.setTimeZone(utcTimeZone);
    }

    private static double metersToDegrees(double distance) {
        return DistanceUtils.dist2Degrees((new Distance(distance,
                LinearUnit.METER).getAs(LinearUnit.KILOMETER)), DistanceUtils.EARTH_MEAN_RADIUS_KM);
    }

    @Override
    public SolrQuery and(List<SolrQuery> operands) {
        SolrQuery query = logicalOperator(operands, AND);
        combineXpathFilterQueries(query, operands, AND);
        return query;
    }

    @Override
    public SolrQuery or(List<SolrQuery> operands) {
        SolrQuery query = logicalOperator(operands, OR);
        combineXpathFilterQueries(query, operands, OR);
        return query;
    }

    private void combineXpathFilterQueries(SolrQuery query, List<SolrQuery> subQueries,
            String operator) {
        List<String> queryParams = new ArrayList<>();
        // Use Set to remove duplicates now that the namespaces have been stripped out
        Set<String> xpathFilters = new TreeSet<>();
        Set<String> xpathIndexes = new TreeSet<>();

        for (SolrQuery subQuery : subQueries) {
            String[] params = subQuery.getParams(FILTER_QUERY_PARAM_NAME);
            if (params != null) {
                for (String param : params) {
                    if (StringUtils.startsWith(param, XPATH_QUERY_PARSER_PREFIX)) {
                        if (StringUtils.contains(param, XPATH_FILTER_QUERY_INDEX)) {
                            xpathIndexes.add(StringUtils.substringAfter(StringUtils.substringBeforeLast(
                                    param,
                                    "\""), XPATH_FILTER_QUERY_INDEX + ":\""));
                        } else if (StringUtils.startsWith(param,
                                XPATH_QUERY_PARSER_PREFIX + XPATH_FILTER_QUERY)) {
                            xpathFilters.add(StringUtils.substringAfter(StringUtils.substringBeforeLast(
                                    param,
                                    "\""), XPATH_FILTER_QUERY + ":\""));
                        }
                    }
                    Collections.addAll(queryParams, param);
                }
            }
        }

        if (xpathFilters.size() > 1) {
            // More than one XPath found, need to combine
            String filter =
                    XPATH_QUERY_PARSER_PREFIX + XPATH_FILTER_QUERY + ":\"(" + StringUtils.join(
                            xpathFilters,
                            operator.toLowerCase()) + ")\"";

            List<String> indexes = new ArrayList<>();
            for (String xpath : xpathIndexes) {
                indexes.add("(" + XPATH_FILTER_QUERY_INDEX + ":\"" + xpath + "\")");
            }
            // TODO DDF-1882 add pre-filter xpath index
            //String index = XPATH_QUERY_PARSER_PREFIX + StringUtils.join(indexes, operator);
            //query.setParam(FILTER_QUERY_PARAM_NAME, filter, index);
            query.setParam(FILTER_QUERY_PARAM_NAME, filter);
        } else if (queryParams.size() > 0) {
            // Pass through original filter queries if only a single XPath is present
            query.setParam(FILTER_QUERY_PARAM_NAME,
                    queryParams.toArray(new String[queryParams.size()]));
        }
    }

    @Override
    public SolrQuery not(SolrQuery operand) {
        return new SolrQuery(" NOT " + operand.getQuery());
    }

    @Override
    public SolrQuery propertyIsFuzzy(String propertyName, String searchPhrase) {
        // On fuzzy searches, no text analysis is performed on the search phrase. Expect fuzzy
        // terms to be case insensitive.
        String fuzzyPhrase = Stream.of(StringUtils.split(searchPhrase))
                .map(String::toLowerCase)
                .collect(Collectors.joining("~ +", "(+", "~)"));

        if (searchPhrase.contains(SOLR_WILDCARD_CHAR) || searchPhrase.contains(
                SOLR_SINGLE_WILDCARD_CHAR) || Metacard.ANY_TEXT.equals(propertyName)) {
            return new SolrQuery(wildcardSolrQuery(fuzzyPhrase, propertyName, false));
        } else {
            String mappedPropertyName = getMappedPropertyName(propertyName,
                    AttributeFormat.STRING,
                    false);

            return new SolrQuery(mappedPropertyName + ":" + fuzzyPhrase);
        }
    }

    @Override
    public SolrQuery propertyIsLike(String propertyName, String pattern, boolean isCaseSensitive) {
        verifyInputData(propertyName, pattern);

        String searchPhrase = escapeSpecialCharacters(pattern);
        if (searchPhrase.contains(SOLR_WILDCARD_CHAR) || searchPhrase.contains(
                SOLR_SINGLE_WILDCARD_CHAR)) {
            searchPhrase = "(" + searchPhrase + ")";
        } else {
            // Not an exact phrase
            searchPhrase = QUOTE + searchPhrase + QUOTE;
        }

        if (searchPhrase.contains(SOLR_WILDCARD_CHAR) || searchPhrase.contains(
                SOLR_SINGLE_WILDCARD_CHAR) || Metacard.ANY_TEXT.equals(propertyName)) {

            return new SolrQuery(wildcardSolrQuery(searchPhrase, propertyName, isCaseSensitive));
        } else {

            String mappedPropertyName = getMappedPropertyName(propertyName,
                    AttributeFormat.STRING,
                    false);
            if (isCaseSensitive) {
                mappedPropertyName = resolver.getCaseSensitiveField(mappedPropertyName);
            }

            return new SolrQuery(mappedPropertyName + ":" + searchPhrase);
        }
    }

    @Override
    public SolrQuery propertyIsEqualTo(String propertyName, String literal,
            boolean isCaseSensitive) {
        if (!isCaseSensitive) {
            throw new UnsupportedOperationException(
                    "Case insensitive exact searches are not supported.");
        }
        verifyInputData(propertyName, literal);

        String searchPhrase = QUOTE + escapeSpecialCharacters(literal) + QUOTE;

        if (searchPhrase.contains(SOLR_WILDCARD_CHAR) || searchPhrase.contains(
                SOLR_SINGLE_WILDCARD_CHAR) || Metacard.ANY_TEXT.equals(propertyName)) {
            return new SolrQuery(wildcardSolrQuery(searchPhrase, propertyName, true));
        } else {
            String mappedPropertyName = getMappedPropertyName(propertyName,
                    AttributeFormat.STRING,
                    true);
            return new SolrQuery(mappedPropertyName + ":" + searchPhrase);
        }
    }

    private String wildcardSolrQuery(String searchPhrase, String propertyName,
            boolean isCaseSensitive) {
        String solrQuery = "";
        if (Metacard.ANY_TEXT.equals(propertyName)) {
            solrQuery = resolver.anyTextFields()
                    .map(resolver::getWhitespaceTokenizedField)
                    .map((whitespaceField) -> {
                        if (isCaseSensitive) {
                            return resolver.getCaseSensitiveField(whitespaceField);
                        } else {
                            return whitespaceField;
                        }
                    })
                    .map((field) -> field + ":" + searchPhrase)
                    .collect(Collectors.joining(" "));
        } else {
            String whitespaceField = resolver.getWhitespaceTokenizedField(getMappedPropertyName(
                    propertyName,
                    AttributeFormat.STRING,
                    true));
            if (isCaseSensitive) {
                whitespaceField = resolver.getCaseSensitiveField(whitespaceField);
            }
            solrQuery = whitespaceField + ":" + searchPhrase;
        }
        return "(" + solrQuery + ")";
    }

    @Override
    public SolrQuery propertyIsEqualTo(String propertyName, Date exactDate) {
        String mappedPropertyName = getMappedPropertyName(propertyName, AttributeFormat.DATE, true);

        SolrQuery query = new SolrQuery();
        query.setQuery(
                " " + mappedPropertyName + ":" + QUOTE + dateFormat.format(exactDate) + QUOTE);

        return query;
    }

    @Override
    public SolrQuery propertyIsEqualTo(String propertyName, int literal) {
        return getEqualToQuery(propertyName, AttributeFormat.INTEGER, literal);
    }

    @Override
    public SolrQuery propertyIsEqualTo(String propertyName, short literal) {
        return getEqualToQuery(propertyName, AttributeFormat.SHORT, literal);
    }

    @Override
    public SolrQuery propertyIsEqualTo(String propertyName, long literal) {
        return getEqualToQuery(propertyName, AttributeFormat.LONG, literal);
    }

    @Override
    public SolrQuery propertyIsEqualTo(String propertyName, float literal) {
        return getEqualToQuery(propertyName, AttributeFormat.FLOAT, literal);
    }

    @Override
    public SolrQuery propertyIsEqualTo(String propertyName, double literal) {
        return getEqualToQuery(propertyName, AttributeFormat.FLOAT, literal);
    }

    @Override
    public SolrQuery propertyIsEqualTo(String propertyName, boolean literal) {
        String mappedPropertyName = getMappedPropertyName(propertyName,
                AttributeFormat.BOOLEAN,
                true);
        return new SolrQuery(mappedPropertyName + ":" + literal);
    }

    @Override
    public SolrQuery propertyIsGreaterThan(String propertyName, Date startDate) {
        String formattedStartDate = formatDate(startDate);
        return buildDateQuery(propertyName,
                SOLR_EXCLUSIVE_START,
                formattedStartDate,
                SOLR_WILDCARD_CHAR,
                SOLR_INCLUSIVE_END);
    }

    @Override
    public SolrQuery propertyIsGreaterThan(String propertyName, int literal) {
        return getGreaterThanQuery(propertyName, AttributeFormat.INTEGER, literal);
    }

    @Override
    public SolrQuery propertyIsGreaterThan(String propertyName, short literal) {
        return getGreaterThanQuery(propertyName, AttributeFormat.SHORT, literal);
    }

    @Override
    public SolrQuery propertyIsGreaterThan(String propertyName, long literal) {
        return getGreaterThanQuery(propertyName, AttributeFormat.LONG, literal);
    }

    @Override
    public SolrQuery propertyIsGreaterThan(String propertyName, float literal) {
        return getGreaterThanQuery(propertyName, AttributeFormat.FLOAT, literal);
    }

    @Override
    public SolrQuery propertyIsGreaterThan(String propertyName, double literal) {
        return getGreaterThanQuery(propertyName, AttributeFormat.DOUBLE, literal);
    }

    @Override
    public SolrQuery propertyIsGreaterThanOrEqualTo(String propertyName, Date startDate) {
        String formattedStartDate = formatDate(startDate);
        return buildDateQuery(propertyName,
                SOLR_INCLUSIVE_START,
                formattedStartDate,
                SOLR_WILDCARD_CHAR,
                SOLR_INCLUSIVE_END);
    }

    @Override
    public SolrQuery propertyIsGreaterThanOrEqualTo(String propertyName, short literal) {
        return getGreaterThanOrEqualToQuery(propertyName, AttributeFormat.SHORT, literal);
    }

    @Override
    public SolrQuery propertyIsGreaterThanOrEqualTo(String propertyName, int literal) {
        return getGreaterThanOrEqualToQuery(propertyName, AttributeFormat.INTEGER, literal);
    }

    @Override
    public SolrQuery propertyIsGreaterThanOrEqualTo(String propertyName, long literal) {
        return getGreaterThanOrEqualToQuery(propertyName, AttributeFormat.LONG, literal);
    }

    @Override
    public SolrQuery propertyIsGreaterThanOrEqualTo(String propertyName, float literal) {
        return getGreaterThanOrEqualToQuery(propertyName, AttributeFormat.FLOAT, literal);
    }

    @Override
    public SolrQuery propertyIsGreaterThanOrEqualTo(String propertyName, double literal) {
        return getGreaterThanOrEqualToQuery(propertyName, AttributeFormat.DOUBLE, literal);
    }

    @Override
    public SolrQuery during(String propertyName, Date startDate, Date endDate) {
        String formattedStartDate = formatDate(startDate);
        String formattedEndDate = formatDate(endDate);
        return buildDateQuery(propertyName,
                SOLR_EXCLUSIVE_START,
                formattedStartDate,
                formattedEndDate,
                SOLR_EXCLUSIVE_END);
    }

    @Override
    public SolrQuery before(String propertyName, Date date) {
        String formattedEndDate = formatDate(date);

        return buildDateQuery(propertyName,
                SOLR_INCLUSIVE_START,
                SOLR_WILDCARD_CHAR,
                formattedEndDate,
                SOLR_EXCLUSIVE_END);
    }

    @Override
    public SolrQuery after(String propertyName, Date startDate) {
        String formattedStartDate = formatDate(startDate);
        return buildDateQuery(propertyName,
                SOLR_EXCLUSIVE_START,
                formattedStartDate,
                SOLR_WILDCARD_CHAR,
                SOLR_INCLUSIVE_END);
    }

    @Override
    public SolrQuery propertyIsLessThan(String propertyName, Date endDate) {
        String formattedEndDate = formatDate(endDate);
        return buildDateQuery(propertyName,
                SOLR_INCLUSIVE_START,
                SOLR_WILDCARD_CHAR,
                formattedEndDate,
                SOLR_EXCLUSIVE_END);
    }

    @Override
    public SolrQuery propertyIsLessThan(String propertyName, int literal) {
        return getLessThanQuery(propertyName, AttributeFormat.INTEGER, literal);
    }

    @Override
    public SolrQuery propertyIsLessThan(String propertyName, short literal) {
        return getLessThanQuery(propertyName, AttributeFormat.SHORT, literal);
    }

    @Override
    public SolrQuery propertyIsLessThan(String propertyName, long literal) {
        return getLessThanQuery(propertyName, AttributeFormat.LONG, literal);
    }

    @Override
    public SolrQuery propertyIsLessThan(String propertyName, float literal) {
        return getLessThanQuery(propertyName, AttributeFormat.FLOAT, literal);
    }

    @Override
    public SolrQuery propertyIsLessThan(String propertyName, double literal) {
        return getLessThanQuery(propertyName, AttributeFormat.DOUBLE, literal);
    }

    @Override
    public SolrQuery propertyIsLessThanOrEqualTo(String propertyName, Date endDate) {
        String formattedEndDate = formatDate(endDate);
        return buildDateQuery(propertyName,
                SOLR_INCLUSIVE_START,
                SOLR_WILDCARD_CHAR,
                formattedEndDate,
                SOLR_INCLUSIVE_END);
    }

    @Override
    public SolrQuery propertyIsLessThanOrEqualTo(String propertyName, short literal) {
        return getLessThanOrEqualToQuery(propertyName, AttributeFormat.SHORT, literal);
    }

    @Override
    public SolrQuery propertyIsLessThanOrEqualTo(String propertyName, int literal) {
        return getLessThanOrEqualToQuery(propertyName, AttributeFormat.INTEGER, literal);
    }

    @Override
    public SolrQuery propertyIsLessThanOrEqualTo(String propertyName, long literal) {
        return getLessThanOrEqualToQuery(propertyName, AttributeFormat.LONG, literal);
    }

    @Override
    public SolrQuery propertyIsLessThanOrEqualTo(String propertyName, float literal) {
        return getLessThanOrEqualToQuery(propertyName, AttributeFormat.FLOAT, literal);
    }

    @Override
    public SolrQuery propertyIsLessThanOrEqualTo(String propertyName, double literal) {
        return getLessThanOrEqualToQuery(propertyName, AttributeFormat.DOUBLE, literal);
    }

    @Override
    public SolrQuery propertyIsBetween(String propertyName, Date lowerBoundary,
            Date upperBoundary) {
        String formattedStartDate = formatDate(lowerBoundary);
        String formattedEndDate = formatDate(upperBoundary);
        // From OGC 09-026r1 and ISO 19143:2010(E), Section 7.7.3.7:
        // The PropertyIsBetween element is defined as a compact way of encoding a range check.
        // The lower and upper boundary values are inclusive.
        return buildDateQuery(propertyName,
                SOLR_INCLUSIVE_START,
                formattedStartDate,
                formattedEndDate,
                SOLR_INCLUSIVE_END);
    }

    @Override
    public SolrQuery relative(String propertyName, long duration) {
        DateTime now = new DateTime();
        Date start = now.minus(duration)
                .toDate();
        Date end = now.toDate();

        String formattedStartDate = formatDate(start);
        String formattedEndDate = formatDate(end);

        return buildDateQuery(propertyName,
                SOLR_INCLUSIVE_START,
                formattedStartDate,
                formattedEndDate,
                SOLR_INCLUSIVE_END);
    }

    private SolrQuery buildDateQuery(String propertyName, String startCondition, String startDate,
            String endDate, String endCondition) {
        SolrQuery query = new SolrQuery();
        query.setQuery(" " + getMappedPropertyName(propertyName, AttributeFormat.DATE, false)
                + startCondition + startDate + TO + endDate + endCondition);
        return query;
    }

    private String formatDate(Date date) {
        return dateFormat.format(date);
    }

    @Override
    public SolrQuery nearestNeighbor(String propertyName, String wkt) {
        Geometry geo = getGeometry(wkt);

        if (geo != null) {
            Point pnt;
            if (isPoint(geo)) {
                pnt = (Point) geo;
            } else {
                pnt = geo.getCentroid();
            }

            SolrQuery query = null;
            if (null != pnt) {
                String nearestNeighborQuery = geoPointToCircleQuery(propertyName,
                        NEAREST_NEIGHBOR_DISTANCE_LIMIT,
                        pnt);

                updateDistanceSort(propertyName, pnt);
                query = new SolrQuery(nearestNeighborQuery);
            }
            return query;
        } else {
            throw new UnsupportedOperationException("Unable to read given WKT: " + wkt);
        }
    }

    private String geoPointToCircleQuery(String propertyName, double distanceInDegrees, Point pnt) {
        String circle = "BUFFER(POINT(" + pnt.getX() + " " + pnt.getY() + "), " + distanceInDegrees + ")";
        String geoIndexName = getMappedPropertyName(propertyName, AttributeFormat.GEOMETRY, false);

        return geoIndexName + ":\"" + INTERSECTS_OPERATION + "(" + circle + ")\"";
    }

    @Override
    public SolrQuery contains(String propertyName, String wkt) {
        return operationToQuery("Contains", propertyName, wkt);
    }

    @Override
    public SolrQuery dwithin(String propertyName, String wkt, double distance) {
        Geometry geo = getGeometry(wkt);

        if (geo != null) {
            double distanceInDegrees = metersToDegrees(distance);
            if (isPoint(geo)) {
                Point pnt = (Point) geo;
                String pointRadiusQuery = geoPointToCircleQuery(propertyName,
                        distanceInDegrees,
                        pnt);

                updateDistanceSort(propertyName, pnt);
                return new SolrQuery(pointRadiusQuery);
            } else {
                Geometry bufferGeo = geo.buffer(distanceInDegrees, QUADRANT_SEGMENTS);
                String bufferWkt = WKT_WRITER.write(bufferGeo);
                return operationToQuery(INTERSECTS_OPERATION, propertyName, bufferWkt);
            }
        } else {
            throw new UnsupportedOperationException("Unable to read given WKT: " + wkt);
        }
    }

    private Geometry getGeometry(String wkt) {
        WKTReader reader = new WKTReader(GEOMETRY_FACTORY);

        Geometry geo = null;
        try {
            geo = reader.read(wkt);
        } catch (ParseException e) {
            LOGGER.info("Failed to read WKT: {}", wkt, e);
        }
        return geo;
    }

    @Override
    public SolrQuery intersects(String propertyName, String wkt) {
        // Bug in spatial4j that does not properly find intersection between
        // points. Therefore, converting to a point-radius to account for the
        // error.
        if (StringUtils.isNotBlank(wkt) && wkt.toUpperCase()
                .contains("POINT")) {
            Geometry geo = getGeometry(wkt);

            if (geo != null) {

                if (isPoint(geo)) {
                    Point pnt = (Point) geo;
                    String pointRadiusQuery = geoPointToCircleQuery(propertyName,
                            DEFAULT_ERROR_IN_DEGREES,
                            pnt);

                    updateDistanceSort(propertyName, pnt);
                    return new SolrQuery(pointRadiusQuery);
                }
                if (MultiPoint.class.getSimpleName()
                        .equals(geo.getGeometryType()) && geo.getCoordinates().length == 1) {
                    Point pnt = GEOMETRY_FACTORY.createPoint(geo.getCoordinate());
                    String pointRadiusQuery = geoPointToCircleQuery(propertyName,
                            DEFAULT_ERROR_IN_DEGREES,
                            pnt);

                    updateDistanceSort(propertyName, pnt);
                    return new SolrQuery(pointRadiusQuery);
                }
            }
        }

        return operationToQuery(INTERSECTS_OPERATION, propertyName, wkt);
    }

    @Override
    public SolrQuery within(String propertyName, String wkt) {
        return operationToQuery("IsWithin", propertyName, wkt);
    }

    @Override
    public SolrQuery disjoint(String propertyName, String wkt) {
        return operationToQuery("IsDisjointTo", propertyName, wkt);
    }

    @Override
    public SolrQuery overlaps(String propertyName, String wkt) {
        return operationToQuery("Overlaps", propertyName, wkt);
    }

    @Override
    public SolrQuery xpathExists(String xpath) {
        return getXPathQuery(xpath, null, true);
    }

    @Override
    public SolrQuery xpathIsLike(String xpath, String pattern, boolean isCaseSensitive) {
        return getXPathQuery(xpath, pattern, isCaseSensitive);
    }

    @Override
    public SolrQuery xpathIsFuzzy(String xpath, String literal) {
        // XPath does not support fuzzy matching, doing best effort case-insensitive evaluation instead
        return getXPathQuery(xpath, literal, false);
    }

    private SolrQuery getXPathQuery(final String pattern, final String searchPhrase,
            final boolean isCaseSensitive) {
        // TODO should use XPath parser to make sure to only remove namespace pattern from path and not quoted text
        // replace quotes and remove namespaces
        String xpath = pattern.replace("\"", "'")
                .replaceAll("[\\w]+:(?!:)", "");
        String query = "*:*";

        if (StringUtils.isNotBlank(searchPhrase)) {
            String result = ".";
            String phrase = searchPhrase;
            if (!isCaseSensitive) {
                result = "lower-case(" + result + ")";
                phrase = phrase.toLowerCase();
            }
            xpath = xpath + "[contains(" + result + ", '" + phrase + "')]";
            query = resolver.getField(Metacard.METADATA, AttributeFormat.STRING, false) + ":\""
                    + searchPhrase.replaceAll("\"", "\\\\") + "\"";
        }

        SolrQuery solrQuery = new SolrQuery(query);
        solrQuery.addFilterQuery(
                XPATH_QUERY_PARSER_PREFIX + XPATH_FILTER_QUERY + ":\"" + xpath + "\"");
        // TODO DDF-1882 add pre-filter xpath index
        //        solrQuery.addFilterQuery(
        //                XPATH_QUERY_PARSER_PREFIX + XPATH_FILTER_QUERY + ":\"" + xpath + "\"",
        //                XPATH_QUERY_PARSER_PREFIX + XPATH_FILTER_QUERY_INDEX + ":\"" + xpath + "\"");

        return solrQuery;
    }

    private void updateDistanceSort(String propertyName, Point point) {
        if (sortBy != null && sortBy.getPropertyName() != null) {
            String sortByPropertyName = sortBy.getPropertyName()
                    .getPropertyName();
            if (Result.DISTANCE.equals(sortByPropertyName) || propertyName.equals(sortByPropertyName)) {
                isSortedByDistance = true;
                sortedDistancePoint = point.getY() + "," + point.getX();
            }
        }
    }

    private SolrQuery getEqualToQuery(String propertyName, AttributeFormat format, Number literal) {
        String mappedPropertyName = getMappedPropertyName(propertyName, format, true);

        SolrQuery query = new SolrQuery();
        query.setQuery(" " + mappedPropertyName + ":" + literal.toString());

        return query;
    }

    private SolrQuery getGreaterThanOrEqualToQuery(String propertyName, AttributeFormat format,
            Number literal) {
        String mappedPropertyName = getMappedPropertyName(propertyName, format, true);

        SolrQuery query = new SolrQuery();
        query.setQuery(" " + mappedPropertyName + ":[ " + literal.toString() + TO + "* ] ");

        return query;
    }

    private SolrQuery getGreaterThanQuery(String propertyName, AttributeFormat format,
            Number literal) {
        String mappedPropertyName = getMappedPropertyName(propertyName, format, true);

        SolrQuery query = new SolrQuery();
        query.setQuery(" " + mappedPropertyName + ":{ " + literal.toString() + TO + "* ] ");

        return query;
    }

    private SolrQuery getLessThanOrEqualToQuery(String propertyName, AttributeFormat format,
            Number literal) {
        String mappedPropertyName = getMappedPropertyName(propertyName, format, true);

        SolrQuery query = new SolrQuery();
        query.setQuery(" " + mappedPropertyName + ":[ * TO " + literal.toString() + " ] ");

        return query;
    }

    private SolrQuery getLessThanQuery(String propertyName, AttributeFormat format,
            Number literal) {
        String mappedPropertyName = getMappedPropertyName(propertyName, format, true);

        SolrQuery query = new SolrQuery();
        query.setQuery(" " + mappedPropertyName + ":[ * TO " + literal.toString() + " } ");

        return query;
    }

    // @Override
    // public SolrQuery beyond(String propertyName, String wkt, double distance)
    // {
    // return pointRadiusToQuerySolrQuery("IsDisjointTo", propertyName, wkt,
    // distance);
    // }

    /*
     * If we eventually support the OGC Equals operation, we will need the IsEqualTo Spatial4j
     * string
     */
    // @Override
    // public Object visit(Equals filter, Object data) {
    // return operationToQuery("IsEqualTo",filter,data);
    // }

    private String getMappedPropertyName(String propertyName, AttributeFormat format,
            boolean isSearchedAsExactString) {
        if (propertyName == null) {
            throw new UnsupportedOperationException("Property name should not be null.");
        }

        return resolver.getField(propertyName, format, isSearchedAsExactString);
    }

    /**
     * This builds a "is null" query based on the property name provided.
     *
     * Since no type is provided with this method, we build a OR chained expression with anonymous field names.
     * The actually expression uses a negative ranged query expression.  The null query needs to be used in order for this
     * expression to play well with other nested expressions.  The query used is outlined in:
     * http://stackoverflow.com/questions/17044661/how-to-filter-search-by-values-that-are-not-available/17045097#17045097
     *
     * @param propertyName the property to null check against.
     * @return the solr query with the is null expression.
     */
    @Override
    public SolrQuery propertyIsNull(String propertyName) {
        List<String> possibleFields = resolver.getAnonymousField(propertyName);
        if (possibleFields.isEmpty()) {
            throw new UnsupportedOperationException(
                    "Anonymous Field Property does not exist. " + propertyName);
        }
        List<String> solrExpressions = new ArrayList<>();
        for (String possibleField : possibleFields) {
            solrExpressions.add(" (*:* -" + possibleField + ":[* TO *]) ");
        }
        String fullExpression = StringUtils.join(solrExpressions, " ");
        return new SolrQuery(fullExpression);
    }

    private SolrQuery logicalOperator(List<SolrQuery> operands, String operator) {
        if (operands == null || operands.size() < 1) {
            throw new UnsupportedOperationException(
                    "[" + operator + "] operation must contain 1 or more filters.");
        }

        for (SolrQuery operand : operands) {
            if (operand == null) {
                throw new UnsupportedOperationException("Null operand found");
            }
        }

        int startIndex = 0;
        SolrQuery query = operands.get(startIndex);
        startIndex++;

        if (query == null) {
            throw new UnsupportedOperationException(
                    "Query was not interpreted properly. Query should not be null.");
        }

        StringBuilder builder = new StringBuilder();
        builder.append(START_PAREN);
        builder.append(query.getQuery());

        for (int i = startIndex; i < operands.size(); i++) {
            SolrQuery localQuery = operands.get(i);

            if (localQuery != null) {
                builder.append(operator)
                        .append(localQuery.getQuery());
            } else {
                throw new UnsupportedOperationException(
                        "Query was not interpreted properly. Query should not be null.");
            }

        }
        builder.append(END_PAREN);
        query.setQuery(builder.toString());

        return query;
    }

    private void verifyInputData(String propertyName, String pattern) {
        if (propertyName == null || propertyName.isEmpty()) {
            throw new UnsupportedOperationException("PropertyName is required for search.");
        }
        if (pattern == null || pattern.isEmpty()) {
            throw new UnsupportedOperationException("Literal value is required for search.");
        }
    }

    private String escapeSpecialCharacters(String searchPhrase) {
        return StringUtils.replaceEach(searchPhrase,
                LUCENE_SPECIAL_CHARACTERS,
                ESCAPED_LUCENE_SPECIAL_CHARACTERS);
    }

    private SolrQuery operationToQuery(String operation, String propertyName, String wkt) {
        String geoIndexName = getMappedPropertyName(propertyName, AttributeFormat.GEOMETRY, false);

        if (!StringUtils.isNotEmpty(wkt)) {
            throw new UnsupportedOperationException("Wkt should not be null or empty.");
        }

        String geoQuery = geoIndexName + ":\"" + operation + "(" + wkt + ")\"";

        Geometry pnt = getGeometry(wkt);
        if (pnt != null) {
            updateDistanceSort(propertyName, pnt.getCentroid());
        }

        return new SolrQuery(geoQuery);
    }

    private boolean isPoint(Geometry geo) {
        return geo != null && Point.class.getSimpleName()
                .equals(geo.getGeometryType());
    }

    public void setSortPolicy(SortBy sort) {
        this.sortBy = sort;
    }

    public boolean isSortedByDistance() {
        return isSortedByDistance;
    }

    public String getSortedDistancePoint() {
        return sortedDistancePoint;
    }
}
