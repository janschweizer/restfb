/*
 * Copyright (c) 2010 Mark Allen.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.restfb;

import static java.net.HttpURLConnection.HTTP_OK;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import com.restfb.WebRequestor.Response;
import com.restfb.json.JSONArray;
import com.restfb.json.JSONException;
import com.restfb.json.JSONObject;

/**
 * TODO: Documentation
 * 
 * @author <a href="http://restfb.com">Mark Allen</a>
 */
public class DefaultFacebookClient implements FacebookClient {
  /**
   * Graph API access token.
   */
  private String accessToken;

  /**
   * Handles {@code GET}s and {@code POST}s to the Facebook API endpoint.
   */
  private WebRequestor webRequestor;

  /**
   * Handles mapping Facebook response JSON to Java objects.
   */
  private JsonMapper jsonMapper;

  /**
   * Set of parameter names that user must not specify themselves, since we use
   * these parameters internally.
   */
  private Set<String> illegalParamNames;

  /**
   * API endpoint URL.
   */
  private static final String FACEBOOK_GRAPH_ENDPOINT_URL =
      "https://graph.facebook.com";

  /**
   * Legacy API endpoint URL, used to support FQL queries.
   */
  private static final String FACEBOOK_LEGACY_ENDPOINT_URL =
      "https://api.facebook.com/method";

  /**
   * Reserved access token parameter name.
   */
  private static final String ACCESS_TOKEN_PARAM_NAME = "access_token";

  /**
   * Reserved method override parameter name.
   */
  private static final String METHOD_PARAM_NAME = "method";

  /**
   * Reserved "multiple IDs" parameter name.
   */
  private static final String IDS_PARAM_NAME = "ids";

  /**
   * Reserved FQL query parameter name.
   */
  private static final String QUERY_PARAM_NAME = "query";

  /**
   * Reserved FQL multiquery parameter name.
   */
  private static final String QUERIES_PARAM_NAME = "queries";

  /**
   * Reserved "result format" parameter name.
   */
  private static final String FORMAT_PARAM_NAME = "format";

  private static final Logger logger =
      Logger.getLogger(DefaultFacebookClient.class);

  /**
   * TODO: Documentation
   */
  public DefaultFacebookClient(String accessToken) {
    this(accessToken, new DefaultJsonMapper(), new DefaultWebRequestor());
  }

  public DefaultFacebookClient(String accessToken, JsonMapper jsonMapper,
      WebRequestor webRequestor) {
    verifyParameterPresence("accessToken", accessToken);
    verifyParameterPresence("jsonMapper", jsonMapper);
    verifyParameterPresence("webRequestor", webRequestor);

    this.accessToken = accessToken;
    this.webRequestor = webRequestor;
    this.jsonMapper = jsonMapper;

    illegalParamNames = new HashSet<String>();
    illegalParamNames.addAll(Arrays.asList(new String[] {
        ACCESS_TOKEN_PARAM_NAME, METHOD_PARAM_NAME, FORMAT_PARAM_NAME }));
  }

  /**
   * @see com.restfb.FacebookClient#deleteObject(java.lang.String)
   */
  @Override
  public void deleteObject(String object) throws FacebookException {
    throw new UnsupportedOperationException("TODO: implement");
  }

  /**
   * @see com.restfb.FacebookClient#fetchConnection(java.lang.String,
   *      java.lang.Class, com.restfb.Parameter[])
   */
  @Override
  public <T> Connection<T> fetchConnection(String connection,
      Class<T> connectionType, Parameter... parameters)
      throws FacebookException {
    verifyParameterPresence("connection", connection);
    verifyParameterPresence("connectionType", connectionType);

    List<T> data = new ArrayList<T>();
    boolean hasPrevious = false;
    boolean hasNext = false;

    try {
      JSONObject jsonObject =
          new JSONObject(makeRequest(connection, parameters));

      // Pull out data
      JSONArray jsonData = jsonObject.getJSONArray("data");
      for (int i = 0; i < jsonData.length(); i++)
        data.add(jsonMapper.toJavaObject(jsonData.get(i).toString(),
          connectionType));

      // Pull out paging info, if present
      if (jsonObject.has("paging")) {
        JSONObject jsonPaging = jsonObject.getJSONObject("paging");
        hasPrevious = jsonPaging.has("previous");
        hasNext = jsonPaging.has("next");
      }
    } catch (JSONException e) {
      throw new FacebookJsonMappingException(
        "Unable to map connection JSON to Java objects", e);
    }

    return new Connection<T>(data, hasPrevious, hasNext);
  }

  /**
   * @see com.restfb.FacebookClient#fetchObject(java.lang.String,
   *      java.lang.Class, com.restfb.Parameter[])
   */
  @Override
  public <T> T fetchObject(String object, Class<T> objectType,
      Parameter... parameters) throws FacebookException {
    verifyParameterPresence("object", object);
    verifyParameterPresence("objectType", objectType);
    return jsonMapper.toJavaObject(makeRequest(object, parameters), objectType);
  }

  /**
   * @see com.restfb.FacebookClient#fetchObjects(java.util.List,
   *      java.lang.Class, com.restfb.Parameter[])
   */
  @Override
  public <T> List<T> fetchObjects(List<String> ids, Class<T> objectType,
      Parameter... parameters) throws FacebookException {
    verifyParameterPresence("ids", ids);
    verifyParameterPresence("connectionType", objectType);

    if (ids.size() == 0)
      throw new IllegalArgumentException("The list of IDs cannot be empty.");

    for (Parameter parameter : parameters)
      if (IDS_PARAM_NAME.equals(parameter.name))
        throw new IllegalArgumentException("You cannot specify the '"
            + IDS_PARAM_NAME + "' URL parameter yourself - "
            + "RestFB will populate this for you with "
            + "the list of IDs you passed to this method.");

    // Normalize the IDs
    for (int i = 0; i < ids.size(); i++) {
      String id = ids.get(i).trim().toLowerCase();
      if ("".equals(id))
        throw new IllegalArgumentException(
          "The list of IDs cannot contain blank strings.");
      ids.set(i, id);
    }

    try {
      JSONObject jsonObject =
          new JSONObject(makeRequest("", parametersWithAdditionalParameter(
            Parameter.with(IDS_PARAM_NAME, StringUtils.join(ids)), parameters)));

      List<T> objects = new ArrayList<T>();
      for (String id : ids)
        if (jsonObject.has(id))
          objects.add(jsonMapper.toJavaObject(jsonObject.get(id).toString(),
            objectType));

      return Collections.unmodifiableList(objects);
    } catch (JSONException e) {
      throw new FacebookJsonMappingException(
        "Unable to map connection JSON to Java objects", e);
    }
  }

  /**
   * @see com.restfb.FacebookClient#publish(java.lang.String,
   *      com.restfb.Parameter[])
   */
  @Override
  public void publish(String connection, Parameter... parameters)
      throws FacebookException {
    throw new UnsupportedOperationException("TODO: implement");
  }

  /**
   * @see com.restfb.FacebookClient#executeMultiquery(java.util.Map,
   *      java.lang.Class, com.restfb.Parameter[])
   */
  @Override
  public <T> T executeMultiquery(Map<String, String> queries,
      Class<T> objectType, Parameter... parameters) throws FacebookException {
    verifyParameterPresence("objectType", objectType);

    for (Parameter parameter : parameters)
      if (QUERIES_PARAM_NAME.equals(parameter.name))
        throw new IllegalArgumentException("You cannot specify the '"
            + QUERIES_PARAM_NAME + "' URL parameter yourself - "
            + "RestFB will populate this for you with "
            + "the queries you passed to this method.");

    try {
      JSONArray jsonArray =
          new JSONArray(makeRequest("fql.multiquery", true, false,
            parametersWithAdditionalParameter(Parameter.with(
              QUERIES_PARAM_NAME, queriesToJson(queries)), parameters)));

      JSONObject normalizedJson = new JSONObject();

      for (int i = 0; i < jsonArray.length(); i++) {
        JSONObject jsonObject = jsonArray.getJSONObject(i);
        normalizedJson.put(jsonObject.getString("name"), jsonObject
          .getJSONArray("fql_result_set"));
      }

      return jsonMapper.toJavaObject(normalizedJson.toString(), objectType);
    } catch (JSONException e) {
      throw new FacebookJsonMappingException(
        "Unable to process fql.multiquery JSON response", e);
    }
  }

  /**
   * @see com.restfb.FacebookClient#executeQuery(java.lang.String,
   *      java.lang.Class, com.restfb.Parameter[])
   */
  @Override
  public <T> List<T> executeQuery(String query, Class<T> objectType,
      Parameter... parameters) throws FacebookException {
    verifyParameterPresence("query", query);
    verifyParameterPresence("objectType", objectType);

    for (Parameter parameter : parameters)
      if (QUERY_PARAM_NAME.equals(parameter.name))
        throw new IllegalArgumentException("You cannot specify the '"
            + QUERY_PARAM_NAME + "' URL parameter yourself - "
            + "RestFB will populate this for you with "
            + "the query you passed to this method.");

    return jsonMapper.toJavaList(makeRequest("fql.query", true, false,
      parametersWithAdditionalParameter(
        Parameter.with(QUERY_PARAM_NAME, query), parameters)), objectType);
  }

  /**
   * Coordinates the process of executing the API request GET/POST and
   * processing the response we receive from the endpoint.
   * 
   * @param endpoint
   *          Facebook Graph API endpoint.
   * @param parameters
   *          Arbitrary number of parameters to send along to Facebook as part
   *          of the API call.
   * @return The JSON returned by Facebook for the API call.
   * @throws FacebookException
   *           If an error occurs while making the Facebook API POST or
   *           processing the response.
   */
  protected String makeRequest(String endpoint, Parameter... parameters)
      throws FacebookException {
    return makeRequest(endpoint, false, false, parameters);
  }

  /**
   * Coordinates the process of executing the API request GET/POST and
   * processing the response we receive from the endpoint.
   * 
   * @param endpoint
   *          Facebook Graph API endpoint.
   * @param useLegacyEndpoint
   *          Should we hit the legacy endpoint ({@code true}) or the new Graph
   *          endpoint ({@code false})?
   * @param executeAsPost
   *          {@code true} to execute the web request as a {@code POST}, {@code
   *          false} to execute as a {@code GET}.
   * @param parameters
   *          Arbitrary number of parameters to send along to Facebook as part
   *          of the API call.
   * @return The JSON returned by Facebook for the API call.
   * @throws FacebookException
   *           If an error occurs while making the Facebook API POST or
   *           processing the response.
   */
  protected String makeRequest(String endpoint, boolean useLegacyEndpoint,
      boolean executeAsPost, Parameter... parameters) throws FacebookException {
    verifyParameterLegality(parameters);

    StringUtils.trimToEmpty(endpoint).toLowerCase();
    if (!endpoint.startsWith("/"))
      endpoint = "/" + endpoint;

    Response response = null;
    String parameterString =
        toParameterString(parametersWithAdditionalParameter(Parameter.with(
          FORMAT_PARAM_NAME, "json"), parameters));
    String fullEndpoint =
        (useLegacyEndpoint ? FACEBOOK_LEGACY_ENDPOINT_URL
            : FACEBOOK_GRAPH_ENDPOINT_URL)
            + endpoint;

    // Perform a GET or POST to the API endpoint
    try {
      response =
          executeAsPost ? webRequestor.executePost(fullEndpoint,
            parameterString) : webRequestor.executeGet(fullEndpoint
              + parameterString);
    } catch (Throwable t) {
      throw new FacebookNetworkException("Facebook GET failed", t);
    }

    if (logger.isInfoEnabled())
      logger.info("Facebook responded with " + response);

    // If we get any HTTP response code other than a 200 OK, throw an exception
    if (HTTP_OK != response.getStatusCode())
      throw new FacebookNetworkException("Facebook GET failed", response
        .getStatusCode());

    String json = response.getBody();

    // If the response contained an error code, throw an exception
    throwFacebookResponseStatusExceptionIfNecessary(json);

    return json;
  }

  /**
   * TODO: Documentation
   * 
   * @param json
   *          The JSON returned by Facebook in response to an API call.
   * @throws FacebookResponseStatusException
   *           If the JSON contains an error code.
   * @throws FacebookJsonMappingException
   *           If an error occurs while processing the JSON.
   */
  protected void throwFacebookResponseStatusExceptionIfNecessary(String json)
      throws FacebookResponseStatusException, FacebookJsonMappingException {
  // TODO: implement - no-op for now. Keep in mind that Graph API and fql API
  // errors are different!

  // This is what an error object looks like in the Graph API:

  /*
   * { "error": { "type": "Exception", "message":
   * "You can only access the \"home\" connection for the current user: " } }
   */

  // This is what an error object looks like in the legacy API:

  /*
   * {"error_code":601,"error_msg":"Parser error: SELECT * is not supported. Please manually list the columns you are interested in."
   * ,
   * "request_args":[{"key":"method","value":"fql.query"},{"key":"token","value"
   * :
   * "123123123"},{"key":"query","value":"select * from user where uid=1233"},{
   * "key" :"format","value":"json"}]}
   */
  }

  /**
   * Generate the parameter string to be included in the Facebook API request.
   * 
   * @param parameters
   *          Arbitrary number of extra parameters to include in the request.
   * @return The parameter string to include in the Facebook API request.
   * @throws FacebookJsonMappingException
   *           If an error occurs when building the parameter string.
   */
  protected String toParameterString(Parameter... parameters)
      throws FacebookJsonMappingException {
    StringBuilder parameterStringBuilder = new StringBuilder();
    boolean first = true;

    List<Parameter> parametersWithAccessToken =
        new ArrayList<Parameter>(Arrays.asList(parameters));
    parametersWithAccessToken.add(Parameter.with(ACCESS_TOKEN_PARAM_NAME,
      accessToken));

    for (Parameter parameter : parametersWithAccessToken) {
      if (first)
        first = false;
      else
        parameterStringBuilder.append("&");

      parameterStringBuilder.append(StringUtils.urlEncode(parameter.name));
      parameterStringBuilder.append("=");
      parameterStringBuilder.append(StringUtils.urlEncode(parameter.value));
    }

    return parameters.length == 0 ? "" : "?" + parameterStringBuilder;
  }

  protected Parameter[] parametersWithAdditionalParameter(Parameter parameter,
      Parameter... parameters) {
    Parameter[] updatedParameters = new Parameter[parameters.length + 1];
    System.arraycopy(parameters, 0, updatedParameters, 0, parameters.length);
    updatedParameters[parameters.length] = parameter;
    return updatedParameters;
  }

  protected String queriesToJson(Map<String, String> queries) {
    verifyParameterPresence("queries", queries);

    if (queries.keySet().size() == 0)
      throw new IllegalArgumentException("You must specify at least one query.");

    JSONObject jsonObject = new JSONObject();

    for (Entry<String, String> entry : queries.entrySet()) {
      if (StringUtils.isBlank(entry.getKey())
          || StringUtils.isBlank(entry.getValue()))
        throw new IllegalArgumentException(
          "Provided queries must have non-blank keys and values. "
              + "You provided: " + queries);

      try {
        jsonObject.put(StringUtils.trimToEmpty(entry.getKey()), StringUtils
          .trimToEmpty(entry.getValue()));
      } catch (JSONException e) {
        // Shouldn't happen unless bizarre input is provided
        throw new IllegalArgumentException("Unable to convert " + queries
            + " to JSON.", e);
      }
    }

    return jsonObject.toString();
  }

  /**
   * Verifies that the provided parameter names don't collide with the ones we
   * internally pass along to Facebook.
   * 
   * @param parameters
   *          The parameters to check.
   * @throws IllegalArgumentException
   *           If there's a parameter name collision.
   */
  protected void verifyParameterLegality(Parameter... parameters) {
    for (Parameter parameter : parameters)
      if (illegalParamNames.contains(parameter.name))
        throw new IllegalArgumentException("Parameter '" + parameter.name
            + "' is reserved for RestFB use - "
            + "you cannot specify it yourself.");
  }

  /**
   * Ensures that {@code parameter} isn't {@code null} or an empty string.
   * 
   * @param parameterName
   *          The name of the parameter (to be used in exception message).
   * @param parameter
   *          The parameter to check.
   * @throws IllegalArgumentException
   *           If {@code parameter} is {@code null} or an empty string.
   */
  protected void verifyParameterPresence(String parameterName, String parameter) {
    verifyParameterPresence(parameterName, (Object) parameter);
    if (parameter.trim().length() == 0)
      throw new IllegalArgumentException("The '" + parameterName
          + "' parameter cannot be an empty string.");
  }

  /**
   * Ensures that {@code parameter} isn't {@code null}.
   * 
   * @param parameterName
   *          The name of the parameter (to be used in exception message).
   * @param parameter
   *          The parameter to check.
   * @throws IllegalArgumentException
   *           If {@code parameter} is {@code null}.
   */
  protected void verifyParameterPresence(String parameterName, Object parameter) {
    if (parameter == null)
      throw new NullPointerException("The '" + parameterName
          + "' parameter cannot be null.");
  }
}