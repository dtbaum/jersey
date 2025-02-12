/*
 * Copyright (c) 2010, 2023 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.uri;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import org.glassfish.jersey.internal.guava.Preconditions;
import org.glassfish.jersey.uri.internal.UriPart;
import org.glassfish.jersey.uri.internal.UriTemplateParser;

/**
 * A URI template.
 *
 * @author Paul Sandoz
 * @author Martin Matula
 * @author Gerard Davison (gerard.davison at oracle.com)
 */
public class UriTemplate {
    private static final String[] EMPTY_VALUES = new String[0];

    /**
     * Order the templates according to JAX-RS specification.
     * <p>
     * Sort the set of matching resource classes using the number of
     * characters in the regular expression not resulting from template
     * variables as the primary key, the number of matching groups
     * as a secondary key, and the number of explicit regular expression
     * declarations as the tertiary key.
     * </p>
     */
    public static final Comparator<UriTemplate> COMPARATOR = new Comparator<UriTemplate>() {

        @Override
        public int compare(UriTemplate o1, UriTemplate o2) {
            if (o1 == null && o2 == null) {
                return 0;
            }
            if (o1 == null) {
                return 1;
            }
            if (o2 == null) {
                return -1;
            }

            if (o1 == EMPTY && o2 == EMPTY) {
                return 0;
            }
            if (o1 == EMPTY) {
                return 1;
            }
            if (o2 == EMPTY) {
                return -1;
            }

            // Compare the number of explicit characters
            // Note that it is important that o2 is compared against o1
            // so that a regular expression with say 10 explicit characters
            // is less than a regular expression with say 5 explicit characters.
            int i = o2.getNumberOfExplicitCharacters() - o1.getNumberOfExplicitCharacters();
            if (i != 0) {
                return i;
            }

            // If the number of explicit characters is equal
            // compare the number of template variables
            // Note that it is important that o2 is compared against o1
            // so that a regular expression with say 10 template variables
            // is less than a regular expression with say 5 template variables.
            i = o2.getNumberOfTemplateVariables() - o1.getNumberOfTemplateVariables();
            if (i != 0) {
                return i;
            }

            // If the number of template variables is equal
            // compare the number of explicit regexes
            i = o2.getNumberOfExplicitRegexes() - o1.getNumberOfExplicitRegexes();
            if (i != 0) {
                return i;
            }

            // If the number of explicit characters and template variables
            // are equal then
            // the order does not matter as long as templates with different
            // explicit characters are distinguishable
            return 0;
        }
    };

    /**
     * A strategy interface for processing parameters, should be replaced with
     * a JDK 8 one day in the future.
     */
    private static interface TemplateValueStrategy {
        /**
         * Get a value for a given template variable.
         *
         * @param templateVariable template variable.
         * @param matchedGroup     matched group string for a given template variable.
         * @return template value.
         *
         * @throws java.lang.IllegalArgumentException in case no value has been found and the strategy
         *                                            does not support {@code null} values.
         */
        public String valueFor(UriPart templateVariable, String matchedGroup);
    }

    /**
     * The regular expression for matching URI templates and names.
     */
    private static final Pattern TEMPLATE_NAMES_PATTERN = Pattern.compile("\\{([\\w\\?;][-\\w\\.,]*)\\}");

    /**
     * The empty URI template that matches the {@code null} or empty URI path.
     */
    public static final UriTemplate EMPTY = new UriTemplate();
    /**
     * The URI template.
     */
    private final String template;
    /**
     * The normalized URI template. Any explicit regex are removed to leave
     * the template variables.
     */
    private final String normalizedTemplate;
    /**
     * The pattern generated from the template.
     */
    private final PatternWithGroups pattern;
    /**
     * True if the URI template ends in a '/' character.
     */
    private final boolean endsWithSlash;
    /**
     * The template variables in the URI template.
     */
    private final List<UriPart> templateVariables;

    /**
     * Get all UriParts, not only the variables
     */
    private final List<UriPart> uriParts;
    /**
     * The number of explicit regular expressions declared for template
     * variables.
     */
    private final int numOfExplicitRegexes;

    /**
     * The number of regular expression groups in this pattern.
     */
    private final int numOfRegexGroups;

    /**
     * The number of characters in the regular expression not resulting
     * from conversion of template variables.
     */
    private final int numOfCharacters;

    /**
     * Constructor for {@code NULL} template.
     */
    private UriTemplate() {
        this.template = this.normalizedTemplate = "";
        this.pattern = PatternWithGroups.EMPTY;
        this.endsWithSlash = false;
        this.templateVariables = Collections.emptyList();
        this.uriParts = Collections.emptyList();
        this.numOfExplicitRegexes = this.numOfCharacters = this.numOfRegexGroups = 0;
    }

    /**
     * Construct a new URI template.
     * <p>
     * The template will be parsed to extract template variables.
     * </p>
     * <p>
     * A specific regular expression will be generated from the template
     * to match URIs according to the template and map template variables to
     * template values.
     * </p>
     *
     * @param template the template.
     * @throws PatternSyntaxException   if the specified
     *                                  regular expression could not be generated
     * @throws IllegalArgumentException if the template is {@code null} or
     *                                  an empty string.
     */
    @SuppressWarnings("DuplicateThrows")
    public UriTemplate(String template) throws PatternSyntaxException, IllegalArgumentException {
        this(new UriTemplateParser(template));
    }

    /**
     * Construct a new URI template.
     * <p>
     * The template will be parsed to extract template variables.
     * <p>
     * A specific regular expression will be generated from the template
     * to match URIs according to the template and map template variables to
     * template values.
     * <p>
     *
     * @param templateParser the parser to parse the template.
     * @throws PatternSyntaxException   if the specified
     *                                  regular expression could not be generated
     * @throws IllegalArgumentException if the template is {@code null} or
     *                                  an empty string.
     */
    @SuppressWarnings("DuplicateThrows")
    protected UriTemplate(UriTemplateParser templateParser) throws PatternSyntaxException, IllegalArgumentException {
        this.template = templateParser.getTemplate();

        this.normalizedTemplate = templateParser.getNormalizedTemplate();

        this.pattern = initUriPattern(templateParser);

        this.numOfExplicitRegexes = templateParser.getNumberOfExplicitRegexes();

        this.numOfRegexGroups = templateParser.getNumberOfRegexGroups();

        this.numOfCharacters = templateParser.getNumberOfLiteralCharacters();

        this.endsWithSlash = template.charAt(template.length() - 1) == '/';

        this.templateVariables = Collections.unmodifiableList(templateParser.getNames());

        this.uriParts = templateParser.getUriParts();
    }

    /**
     * Create the URI pattern from a URI template parser.
     *
     * @param templateParser the URI template parser.
     * @return the URI pattern.
     */
    private static PatternWithGroups initUriPattern(UriTemplateParser templateParser) {
        return new PatternWithGroups(templateParser.getPattern(), templateParser.getGroupIndexes());
    }

    /**
     * Resolve a relative URI reference against a base URI as defined in
     * <a href="http://tools.ietf.org/html/rfc3986#section-5.4">RFC 3986</a>.
     *
     * @param baseUri base URI to be used for resolution.
     * @param refUri  reference URI string to be resolved against the base URI.
     * @return resolved URI.
     *
     * @throws IllegalArgumentException If the given string violates the URI specification RFC.
     */
    public static URI resolve(final URI baseUri, String refUri) {
        return resolve(baseUri, URI.create(refUri));
    }

    /**
     * Resolve a relative URI reference against a base URI as defined in
     * <a href="http://tools.ietf.org/html/rfc3986#section-5.4">RFC 3986</a>.
     *
     * @param baseUri base URI to be used for resolution.
     * @param refUri  reference URI to be resolved against the base URI.
     * @return resolved URI.
     */
    public static URI resolve(final URI baseUri, URI refUri) {
        Preconditions.checkNotNull(baseUri, "Input base URI parameter must not be null.");
        Preconditions.checkNotNull(refUri, "Input reference URI parameter must not be null.");

        final String refString = refUri.toString();
        if (refString.isEmpty()) {
            // we need something to resolve against
            refUri = URI.create("#");
        } else if (refString.startsWith("?")) {
            String baseString = baseUri.toString();
            final int qIndex = baseString.indexOf('?');
            baseString = qIndex > -1 ? baseString.substring(0, qIndex) : baseString;
            return URI.create(baseString + refString);
        }

        URI result = baseUri.resolve(refUri);
        if (refString.isEmpty()) {
            final String resolvedString = result.toString();
            result = URI.create(resolvedString.substring(0, resolvedString.indexOf('#')));
        }

        return normalize(result);
    }

    /**
     * Normalize the URI by resolve the dot & dot-dot path segments as described in
     * <a href="http://tools.ietf.org/html/rfc3986#section-5.2.4">RFC 3986</a>.
     *
     * This method provides a workaround for issues with {@link java.net.URI#normalize()} which
     * is not able to properly normalize absolute paths that start with a {@code ".."} segment,
     * e.g. {@code "/../a/b"} as required by RFC 3986 (according to RFC 3986 the path {@code "/../a/b"}
     * should resolve to {@code "/a/b"}, while {@code URI.normalize()} keeps the {@code ".."} segment
     * in the URI path.
     *
     * @param uri the original URI string.
     * @return the URI with dot and dot-dot segments resolved.
     *
     * @throws IllegalArgumentException If the given string violates the URI specification RFC.
     * @see java.net.URI#normalize()
     */
    public static URI normalize(final String uri) {
        return normalize(URI.create(uri));
    }

    /**
     * Normalize the URI by resolve the dot & dot-dot path segments as described in
     * <a href="http://tools.ietf.org/html/rfc3986#section-5.2.4">RFC 3986</a>.
     *
     * This method provides a workaround for issues with {@link java.net.URI#normalize()} which
     * is not able to properly normalize absolute paths that start with a {@code ".."} segment,
     * e.g. {@code "/../a/b"} as required by RFC 3986 (according to RFC 3986 the path {@code "/../a/b"}
     * should resolve to {@code "/a/b"}, while {@code URI.normalize()} keeps the {@code ".."} segment
     * in the URI path.
     *
     * @param uri the original URI.
     * @return the URI with dot and dot-dot segments resolved.
     *
     * @see java.net.URI#normalize()
     */
    public static URI normalize(final URI uri) {
        Preconditions.checkNotNull(uri, "Input reference URI parameter must not be null.");

        final String path = uri.getPath();

        if (path == null || path.isEmpty() || !path.contains("/.")) {
            return uri;
        }

        final String[] segments = path.split("/");
        final Deque<String> resolvedSegments = new ArrayDeque<String>(segments.length);

        for (final String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                // skip
            } else if ("..".equals(segment)) {
                resolvedSegments.pollLast();
            } else {
                resolvedSegments.offer(segment);
            }
        }

        final StringBuilder pathBuilder = new StringBuilder();
        for (final String segment : resolvedSegments) {
            pathBuilder.append('/').append(segment);
        }

        String resultString = createURIWithStringValues(uri.getScheme(),
                uri.getAuthority(),
                null,
                null,
                null,
                pathBuilder.toString(),
                uri.getQuery(),
                uri.getFragment(),
                EMPTY_VALUES,
                false,
                false);

        return URI.create(resultString);
    }

    /**
     * Relativize URI with respect to a base URI.
     *
     * After the relativization is done, dots in paths of both URIs are {@link #normalize(java.net.URI) resolved}.
     *
     * @param baseUri base URI to be used for relativization.
     * @param refUri  URI to be relativized.
     * @return relativized URI.
     */
    public static URI relativize(URI baseUri, URI refUri) {
        Preconditions.checkNotNull(baseUri, "Input base URI parameter must not be null.");
        Preconditions.checkNotNull(refUri, "Input reference URI parameter must not be null.");

        return normalize(baseUri.relativize(refUri));
    }

    /**
     * Get the URI template as a String.
     *
     * @return the URI template.
     */
    public final String getTemplate() {
        return template;
    }


    /**
     * Get the URI pattern.
     *
     * @return the URI pattern.
     */
    public final PatternWithGroups getPattern() {
        return pattern;
    }

    /**
     * Check if the URI template ends in a slash ({@code '/'}).
     *
     * @return {@code true} if the template ends in a '/', otherwise false.
     */
    @SuppressWarnings("UnusedDeclaration")
    public final boolean endsWithSlash() {
        return endsWithSlash;
    }

    /**
     * Get the list of template variables for the template.
     *
     * @return the list of template variables.
     */
    public final List<String> getTemplateVariables() {
        return templateVariables.stream().map(UriPart::getPart).collect(Collectors.toList());
    }

    /**
     * Ascertain if a template variable is a member of this
     * template.
     *
     * @param name name The template variable.
     * @return {@code true} if the template variable is a member of the template, otherwise {@code false}.
     */
    @SuppressWarnings("UnusedDeclaration")
    public final boolean isTemplateVariablePresent(String name) {
        for (UriPart tv : templateVariables) {
            if (tv.getPart().equals(name)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get the number of explicit regular expressions declared in the template variables.
     *
     * @return the number of explicit regular expressions in the template variables.
     */
    public final int getNumberOfExplicitRegexes() {
        return numOfExplicitRegexes;
    }

    /**
     * Get the number of regular expression groups
     *
     * @return the number of regular expressions groups
     */
    public final int getNumberOfRegexGroups() {
        return numOfRegexGroups;
    }

    /**
     * Get the number of characters in the regular expression not resulting
     * from conversion of template variables.
     *
     * @return the number of explicit characters
     */
    public final int getNumberOfExplicitCharacters() {
        return numOfCharacters;
    }

    /**
     * Get the number of template variables.
     *
     * @return the number of template variables.
     */
    public final int getNumberOfTemplateVariables() {
        return templateVariables.size();
    }

    /**
     * Match a URI against the template.
     * <p>
     * If the URI matches against the pattern then the template variable to value
     * map will be filled with template variables as keys and template values as
     * values.
     * <p>
     *
     * @param uri                     the uri to match against the template.
     * @param templateVariableToValue the map where to put template variables (as keys)
     *                                and template values (as values). The map is cleared before any
     *                                entries are put.
     * @return true if the URI matches the template, otherwise false.
     *
     * @throws IllegalArgumentException if the uri or
     *                                  templateVariableToValue is null.
     */
    public final boolean match(CharSequence uri, Map<String, String> templateVariableToValue) throws
            IllegalArgumentException {
        if (templateVariableToValue == null) {
            throw new IllegalArgumentException();
        }

        return pattern.match(uri, getTemplateVariables(), templateVariableToValue);
    }

    /**
     * Match a URI against the template.
     * <p>
     * If the URI matches against the pattern the capturing group values (if any)
     * will be added to a list passed in as parameter.
     * <p>
     *
     * @param uri         the uri to match against the template.
     * @param groupValues the list to store the values of a pattern's
     *                    capturing groups is matching is successful. The values are stored
     *                    in the same order as the pattern's capturing groups.
     * @return true if the URI matches the template, otherwise false.
     *
     * @throws IllegalArgumentException if the uri or
     *                                  templateVariableToValue is null.
     */
    public final boolean match(CharSequence uri, List<String> groupValues) throws
            IllegalArgumentException {
        if (groupValues == null) {
            throw new IllegalArgumentException();
        }

        return pattern.match(uri, groupValues);
    }

    /**
     * Create a URI by substituting any template variables
     * for corresponding template values.
     * <p>
     * A URI template variable without a value will be substituted by the
     * empty string.
     *
     * @param values the map of template variables to template values.
     * @return the URI.
     */
    public final String createURI(final Map<String, String> values) {
        final StringBuilder sb = new StringBuilder();
        resolveTemplate(sb, new TemplateValueStrategy() {
            @Override
            public String valueFor(UriPart templateVariable, String matchedGroup) {
                String value = values.get(templateVariable.getPart());
                if (value == null) {
                    return "";
                }
                return templateVariable.resolve(value, null, false);
            }
        });
        return sb.toString();
    }

    /**
     * Create a URI by substituting any template variables
     * for corresponding template values.
     * <p>
     * A URI template variable without a value will be substituted by the
     * empty string.
     *
     * @param values the array of template values. The values will be
     *               substituted in order of occurrence of unique template variables.
     * @return the URI.
     */
    public final String createURI(String... values) {
        return createURI(values, 0, values.length);
    }

    /**
     * Create a URI by substituting any template variables
     * for corresponding template values.
     * <p>
     * A URI template variable without a value will be substituted by the
     * empty string.
     *
     * @param values the array of template values. The values will be
     *               substituted in order of occurrence of unique template variables.
     * @param offset the offset into the template value array.
     * @param length the length of the template value array.
     * @return the URI.
     */
    public final String createURI(final String[] values, final int offset, final int length) {

        TemplateValueStrategy ns = new TemplateValueStrategy() {
            private final int lengthPlusOffset = length + offset;
            private int v = offset;
            private final Map<String, String> mapValues = new HashMap<String, String>();

            @Override
            public String valueFor(UriPart templateVariable, String matchedGroup) {
                // Check if a template variable has already occurred
                // If so use the value to ensure that two or more declarations of
                // a template variable have the same value
                String tValue = mapValues.get(templateVariable.getPart());
                if (tValue == null) {
                    if (v < lengthPlusOffset) {
                        tValue = values[v++];
                        if (tValue != null) {
                            mapValues.put(templateVariable.getPart(), tValue);
                        }
                    }
                }

                return tValue;
            }
        };

        final StringBuilder sb = new StringBuilder();
        resolveTemplate(sb, ns);
        return sb.toString();
    }

    /**
     * Build a URI based on the parameters provided by the variable name strategy.
     *
     * @param builder            URI string builder to be used.
     * @param valueStrategy      The template value producer strategy to use.
     */
    private void resolveTemplate(StringBuilder builder, TemplateValueStrategy valueStrategy) {
        for (UriPart uriPart : uriParts) {
            if (uriPart.isTemplate()) {
                builder.append(valueStrategy.valueFor(uriPart, uriPart.getGroup()));
            } else {
                builder.append(uriPart.getPart());
            }
        }
    }

    @Override
    public final String toString() {
        return pattern.toString();
    }

    /**
     * Hash code is calculated from String of the regular expression
     * generated from the template.
     *
     * @return the hash code.
     */
    @Override
    public final int hashCode() {
        return pattern.hashCode();
    }

    /**
     * Equality is calculated from the String of the regular expression
     * generated from the templates.
     *
     * @param o the reference object with which to compare.
     * @return true if equals, otherwise false.
     */
    @Override
    public final boolean equals(Object o) {
        if (o instanceof UriTemplate) {
            UriTemplate that = (UriTemplate) o;
            return this.pattern.equals(that.pattern);
        } else {
            return false;
        }
    }

    /**
     * Construct a URI from the component parts each of which may contain
     * template variables.
     * <p>
     * A template values is an Object instance MUST support the toString()
     * method to convert the template value to a String instance.
     * </p>
     *
     * @param scheme            the URI scheme component.
     * @param authority         the URI authority component.
     * @param userInfo          the URI user info component.
     * @param host              the URI host component.
     * @param port              the URI port component.
     * @param path              the URI path component.
     * @param query             the URI query component.
     * @param fragment          the URI fragment component.
     * @param values            the template variable to value map.
     * @param encode            if true encode a template value according to the correspond
     *                          component type of the associated template variable, otherwise
     *                          contextually encode the template value.
     * @param encodeSlashInPath if {@code true}, the slash ({@code '/'}) characters
     *                          in parameter values will be encoded if the template
     *                          is placed in the URI path component, otherwise the slash
     *                          characters will not be encoded in path templates.
     * @return a URI.
     */
    public static String createURI(
            final String scheme, String authority,
            final String userInfo, final String host, final String port,
            final String path, final String query, final String fragment,
            final Map<String, ?> values, final boolean encode, final boolean encodeSlashInPath) {

        return createURI(scheme, authority,
                userInfo, host, port, path, query, fragment,
                new Object[] {}, encode, encodeSlashInPath, values);
    }

    /**
     * Construct a URI from the component parts each of which may contain
     * template variables.
     * <p>
     * A template value is an Object instance that MUST support the toString()
     * method to convert the template value to a String instance.
     * </p>
     *
     * @param scheme            the URI scheme component.
     * @param authority         the URI authority info component.
     * @param userInfo          the URI user info component.
     * @param host              the URI host component.
     * @param port              the URI port component.
     * @param path              the URI path component.
     * @param query             the URI query component.
     * @param fragment          the URI fragment component.
     * @param values            the template variable to value map.
     * @param encode            if true encode a template value according to the correspond
     *                          component type of the associated template variable, otherwise
     *                          contextually encode the template value.
     * @param encodeSlashInPath if {@code true}, the slash ({@code '/'}) characters
     *                          in parameter values will be encoded if the template
     *                          is placed in the URI path component, otherwise the slash
     *                          characters will not be encoded in path templates.
     * @return a URI.
     */
    public static String createURIWithStringValues(
            final String scheme, final String authority,
            final String userInfo, final String host, final String port,
            final String path, final String query, final String fragment,
            final Map<String, ?> values, final boolean encode, final boolean encodeSlashInPath) {

        return createURI(
                scheme, authority, userInfo, host, port, path, query, fragment, EMPTY_VALUES, encode, encodeSlashInPath, values);
    }

    /**
     * Construct a URI from the component parts each of which may contain
     * template variables.
     * <p>
     * The template values are an array of Object and each Object instance
     * MUST support the toString() method to convert the template value to
     * a String instance.
     * </p>
     *
     * @param scheme            the URI scheme component.
     * @param authority         the URI authority component.
     * @param userInfo          the URI user info component.
     * @param host              the URI host component.
     * @param port              the URI port component.
     * @param path              the URI path component.
     * @param query             the URI query component.
     * @param fragment          the URI fragment component.
     * @param values            the array of template values.
     * @param encode            if true encode a template value according to the correspond
     *                          component type of the associated template variable, otherwise
     *                          contextually encode the template value.
     * @param encodeSlashInPath if {@code true}, the slash ({@code '/'}) characters
     *                          in parameter values will be encoded if the template
     *                          is placed in the URI path component, otherwise the slash
     *                          characters will not be encoded in path templates.
     * @return a URI.
     */
    public static String createURI(
            final String scheme, String authority,
            final String userInfo, final String host, final String port,
            final String path, final String query, final String fragment,
            final Object[] values, final boolean encode, final boolean encodeSlashInPath) {

        return createURI(
                scheme, authority,
                userInfo, host, port, path, query, fragment,
                values, encode, encodeSlashInPath, new HashMap<String, Object>());
    }

    /**
     * Construct a URI from the component parts each of which may contain
     * template variables.
     *
     * @param scheme            the URI scheme component.
     * @param authority         the URI authority component.
     * @param userInfo          the URI user info component.
     * @param host              the URI host component.
     * @param port              the URI port component.
     * @param path              the URI path component.
     * @param query             the URI query component.
     * @param fragment          the URI fragment component.
     * @param values            the array of template values.
     * @param encode            if true encode a template value according to the correspond
     *                          component type of the associated template variable, otherwise
     *                          contextually encode the template value.
     * @param encodeSlashInPath if {@code true}, the slash ({@code '/'}) characters
     *                          in parameter values will be encoded if the template
     *                          is placed in the URI path component, otherwise the slash
     *                          characters will not be encoded in path templates.
     * @return a URI.
     */
    public static String createURIWithStringValues(
            final String scheme, final String authority,
            final String userInfo, final String host, final String port,
            final String path, final String query, final String fragment,
            final String[] values, final boolean encode, final boolean encodeSlashInPath) {

        final Map<String, Object> mapValues = new HashMap<String, Object>();
        return createURI(
                scheme, authority, userInfo, host, port, path, query, fragment, values, encode, encodeSlashInPath, mapValues);
    }

    private static String createURI(
            final String scheme, final String authority, final String userInfo, final String host, final String port,
            final String path, final String query, final String fragment, final Object[] values, final boolean encode,
            final boolean encodeSlashInPath, final Map<String, ?> mapValues) {

        final StringBuilder sb = new StringBuilder();
        int offset = 0;

        if (scheme != null) {
            offset = createUriComponent(UriComponent.Type.SCHEME, scheme, values,
                    offset, false, mapValues, sb);
            sb.append(':');
        }

        boolean hasAuthority = false;
        if (notEmpty(userInfo) || notEmpty(host) || notEmpty(port)) {
            hasAuthority = true;
            sb.append("//");

            if (notEmpty(userInfo)) {
                offset = createUriComponent(UriComponent.Type.USER_INFO, userInfo, values,
                        offset, encode, mapValues, sb);
                sb.append('@');
            }

            if (notEmpty(host)) {
                // TODO check IPv6 address
                offset = createUriComponent(UriComponent.Type.HOST, host, values,
                        offset, encode, mapValues, sb);
            }

            if (notEmpty(port)) {
                sb.append(':');
                offset = createUriComponent(UriComponent.Type.PORT, port, values,
                        offset, false, mapValues, sb);
            }
        } else if (notEmpty(authority)) {
            hasAuthority = true;
            sb.append("//");

            offset = createUriComponent(UriComponent.Type.AUTHORITY, authority, values,
                    offset, encode, mapValues, sb);
        }

        if (notEmpty(path) || notEmpty(query) || notEmpty(fragment)) {
            // make sure we append at least the root path if only query or fragment is present
            if (hasAuthority && (path == null || path.isEmpty() || path.charAt(0) != '/')) {
                sb.append('/');
            }

            if (notEmpty(path)) {
                // path template values are treated as path segments unless encodeSlashInPath is false.
                UriComponent.Type t = (encodeSlashInPath) ? UriComponent.Type.PATH_SEGMENT : UriComponent.Type.PATH;

                offset = createUriComponent(t, path, values,
                        offset, encode, mapValues, sb);
            }

            if (notEmpty(query)) {
                int sbLength = sb.length();
                offset = createUriComponent(UriComponent.Type.QUERY_PARAM, query, values,
                        offset, encode, mapValues, sb);
                if (sb.length() > sbLength) {
                    char firstQuery = sb.charAt(sbLength);
                    if (firstQuery != '?' && firstQuery != '&') {
                        sb.insert(sbLength, '?');
                    }
                }
            }

            if (notEmpty(fragment)) {
                sb.append('#');
                createUriComponent(UriComponent.Type.FRAGMENT, fragment, values,
                        offset, encode, mapValues, sb);
            }
        }
        return sb.toString();
    }

    private static boolean notEmpty(String string) {
        return string != null && !string.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static int createUriComponent(final UriComponent.Type componentType,
                                          String template,
                                          final Object[] values,
                                          final int valueOffset,
                                          final boolean encode,
                                          final Map<String, ?> _mapValues,
                                          final StringBuilder b) {

        final Map<String, Object> mapValues = (Map<String, Object>) _mapValues;

        if (template.indexOf('{') == -1) {
            b.append(template);
            return valueOffset;
        }

        // Find all template variables
        UriTemplateParser templateParser = new UriTemplateParser(template);

        class ValuesFromArrayStrategy implements TemplateValueStrategy {
            private int offset = valueOffset;

            @Override
            public String valueFor(UriPart templateVariable, String matchedGroup) {

                Object value = mapValues.get(templateVariable.getPart());
                if (value == null && offset < values.length) {
                    value = values[offset++];
                    mapValues.put(templateVariable.getPart(), value);
                }
                if (value == null && templateVariable.throwWhenNoTemplateArg()) {
                    throw new IllegalArgumentException(
                            String.format("The template variable '%s' has no value", templateVariable.getPart()));
                }
                return templateVariable.resolve(value, componentType, encode);
            }
        }
        ValuesFromArrayStrategy cs = new ValuesFromArrayStrategy();
        new UriTemplate(templateParser).resolveTemplate(b, cs);

        return cs.offset;
    }


    /**
     * Resolves template variables in the given {@code template} from {@code _mapValues}. Resolves only these variables which are
     * defined in the {@code _mapValues} leaving other variables unchanged.
     *
     * @param type       Type of the {@code template} (port, path, query, ...).
     * @param template   Input uri component to resolve.
     * @param encode     True if template values from {@code _mapValues} should be percent encoded.
     * @param _mapValues Map with template variables as keys and template values as values. None of them should be null.
     * @return String with resolved template variables.
     *
     * @throws IllegalArgumentException when {@code _mapValues} value is null.
     */
    @SuppressWarnings("unchecked")
    public static String resolveTemplateValues(final UriComponent.Type type,
                                               String template,
                                               final boolean encode,
                                               final Map<String, ?> _mapValues) {

        if (template == null || template.isEmpty() || template.indexOf('{') == -1) {
            return template;
        }

        final Map<String, Object> mapValues = (Map<String, Object>) _mapValues;

        StringBuilder sb = new StringBuilder();
        // Find all template variables
        new UriTemplate(new UriTemplateParser(template)).resolveTemplate(sb, new TemplateValueStrategy() {
            @Override
            public String valueFor(UriPart templateVariable, String matchedGroup) {

                Object value = mapValues.get(templateVariable.getPart());

                if (value != null) {
                    return templateVariable.resolve(value.toString(), type, encode);
                } else {
                    if (mapValues.containsKey(templateVariable.getPart())) {
                        throw new IllegalArgumentException(
                                String.format("The value associated of the template value map for key '%s' is 'null'.",
                                        templateVariable)
                        );
                    }

                    return matchedGroup;
                }
            }
        });

        return sb.toString();
    }
}
