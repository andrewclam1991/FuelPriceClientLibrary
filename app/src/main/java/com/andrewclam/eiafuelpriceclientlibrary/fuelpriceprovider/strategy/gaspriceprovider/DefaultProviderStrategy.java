package com.andrewclam.eiafuelpriceclientlibrary.fuelpriceprovider.strategy.gaspriceprovider;

import android.Manifest;
import android.accounts.NetworkErrorException;
import android.location.Address;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.util.Log;

import com.andrewclam.eiafuelpriceclientlibrary.fuelpriceprovider.model.FuelPriceData;
import com.andrewclam.eiafuelpriceclientlibrary.fuelpriceprovider.strategy.regionmatcher.DefaultMatcherStrategy;
import com.andrewclam.eiafuelpriceclientlibrary.fuelpriceprovider.strategy.regionmatcher.MatcherStrategy;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.NoSuchElementException;

import io.reactivex.Single;

import static com.andrewclam.eiafuelpriceclientlibrary.fuelpriceprovider.strategy.gaspriceprovider.ProviderStrategy.Constants.BASE_URI;
import static com.andrewclam.eiafuelpriceclientlibrary.fuelpriceprovider.strategy.gaspriceprovider.ProviderStrategy.Constants.PATH_SEARCH;
import static com.andrewclam.eiafuelpriceclientlibrary.fuelpriceprovider.strategy.gaspriceprovider.ProviderStrategy.Constants.PATH_SERIES;
import static com.andrewclam.eiafuelpriceclientlibrary.fuelpriceprovider.strategy.gaspriceprovider.ProviderStrategy.Constants.QUERY_PARAM_API_KEY_KEY;
import static com.andrewclam.eiafuelpriceclientlibrary.fuelpriceprovider.strategy.gaspriceprovider.ProviderStrategy.Constants.QUERY_PARAM_SERIES_ID_KEY;


/**
 * Internal strategy class that provides the concrete implementations
 * of a {@link ProviderStrategy}
 */
public final class DefaultProviderStrategy implements ProviderStrategy {
  // LOG TAG
  private final String TAG = DefaultProviderStrategy.class.getSimpleName();

  /*
   * Private constructor to prevent external instantiation
   * other than the direct enclosing class
   */
  @NonNull
  private final String mApiKey;

  @NonNull
  private final MatcherStrategy mMatcherStrategy = new DefaultMatcherStrategy();

  public DefaultProviderStrategy(@NonNull String apiKey) {
    mApiKey = apiKey;
  }

  @RequiresPermission(Manifest.permission.INTERNET)
  @NonNull
  public Single<FuelPriceData> extractFuelPriceData(@NonNull String jsonResponse) {
    return Single.create(emitter -> {
      if (Strings.isNullOrEmpty(jsonResponse)) {
        emitter.onError(new NoSuchElementException("unable to parse FuelPriceData from empty json"));
        return;
      }

      // Create an instance of the model to hold data
      FuelPriceData data = new FuelPriceData();

      // Try to parse the SAMPLE_JSON_RESPONSE. If there's a problem with the way the JSON
      // is formatted, a JSONException exception object will be thrown.
      // Catch the exception so the app doesn't crash, and print the error message to the logs.
      try {
        // 1) Convert SAMPLE_JSON_RESPONSE String into a JSONObject
        JSONObject rootJsonResponse = new JSONObject(jsonResponse);

        // 2) Extract "series" JSONArray from root
        JSONArray seriesJsonArray = rootJsonResponse.getJSONArray("series");

        // 3) The "series" JSON array to get the dataJsonArray,
        // the array it will just have one object when only one series is requested, so just set at 0
        JSONObject currentSeriesJsonObject = seriesJsonArray.getJSONObject(0);

        // 4) Assign the series ID and dataSetName of the returned json, for verification
        String seriesID = currentSeriesJsonObject.getString("series_id");
        String dataSetName = currentSeriesJsonObject.getString("name");

        // 5) Reference the data json array where all the data is
        JSONArray dataJsonArray = currentSeriesJsonObject.getJSONArray("data");

        // 6) Reference the latest fuel price, get the first element in the array
        JSONArray fuelPriceJsonArray = dataJsonArray.getJSONArray(0);

        // 7) Parse the latest fuel price,
        // date is the element at index 0,
        // and price is at index 1
        long updateTimeStamp = fuelPriceJsonArray.getLong(0);
        double gasPrice = fuelPriceJsonArray.getDouble(1);

        // 8) Assign the extracted data into a gas price item
        data.setDataSetId(seriesID);
        data.setDataSetName(dataSetName);
        data.setPrice(gasPrice);
        data.setUpdateTimeStamp(updateTimeStamp);

      } catch (JSONException e) {
        // If an error is thrown when executing any of the above statements in the "try" block,
        // catch the exception here, so the app doesn't crash. Print a log message
        // with the message from the exception.
        Log.e(TAG, "Problem parsing the gas retail prices JSON results", e);
        emitter.onError(e);
        return;
      }

      emitter.onSuccess(data);

    });
  }

  @NonNull
  @Override
  public Single<String> createFuelDataRequestURL(@NonNull String seriesId) {
    return Single.create(emitter -> {
      if (Strings.isNullOrEmpty(seriesId) || !seriesId.contains("PET.EMM_EPM0")) {
        emitter.onError(new IllegalArgumentException("Invalid EIA data set series id"));
      } else {
        /*
         * Get data set by series id
         * ex.
         * http://api.eia.gov/series/?api_key=12345&series_id=12345
         */
        Uri.Builder builder = BASE_URI.buildUpon();
        builder.appendPath(PATH_SERIES)
            .appendQueryParameter(QUERY_PARAM_API_KEY_KEY, mApiKey)
            .appendQueryParameter(QUERY_PARAM_SERIES_ID_KEY, seriesId);

        Uri fuelDataRequestUri = builder.build();
        String requestUrl = fuelDataRequestUri.toString();
        emitter.onSuccess(requestUrl);
      }
    });
  }

  @NonNull
  @Override
  public Single<String> extractDataSetSeriesId(@NonNull String jsonResponse) {
    return Single.create(emitter -> {
      // If the JSON string is empty or null, then return early.
      if (Strings.isNullOrEmpty(jsonResponse)) {
        emitter.onError(new IllegalArgumentException("no data, jsonResponse can't be empty."));
        return;
      }

      // Create a string variable to store the retrieved seriesId
      String seriesId;

      // Try to parse the SAMPLE_JSON_RESPONSE. If there's a problem with the way the JSON
      // is formatted, a JSONException exception object will be thrown.
      // Catch the exception so the app doesn't crash, and print the error message to the logs.
      try {
        // 1) Convert SAMPLE_JSON_RESPONSE String into a JSONObject
        JSONObject rootJsonResponse = new JSONObject(jsonResponse);

        // 2) Get the response object
        JSONObject queryResponseJsonObject = rootJsonResponse.getJSONObject("response");

        // 3) Get the docs array
        JSONArray dataJsonArray = queryResponseJsonObject.getJSONArray("docs");

        // 4) Get the most likely candidate
        JSONObject dataJsonObject = dataJsonArray.getJSONObject(0);

        // 5) Parse the series_id
        seriesId = dataJsonObject.getString("series_id");

      } catch (JSONException e) {
        // If an error is thrown when executing any of the above statements in the "try" block,
        // catch the exception here, so the app doesn't crash. Print a log message
        // with the message from the exception.
        Log.e(TAG, "Problem parsing the series id from the JSON results", e);
        emitter.onError(e);
        return;
      }

      if (Strings.isNullOrEmpty(seriesId)) {
        emitter.onError(new IllegalArgumentException("series id can't be null or empty"));
      } else {
        // Return the series id
        emitter.onSuccess(seriesId);
      }
    });
  }

  @NonNull
  @Override
  public Single<String> createSeriesIdRequestURL(@NonNull Address address) {
    return Single.create(emitter -> {
      String dataSetName = getDataSetName(address);
      /*
       * Search possible series id by name
       * ex.
       * http://api.eia.gov/search/?search_term=name&search_value=
       */
      String QUERY_PARAM_SEARCH_TERM_KEY = "search_term";
      String QUERY_PARAM_SEARCH_TERM_VALUE_NAME = "name";
      String QUERY_PARAM_SEARCH_VALUE_KEY = "search_value";

      Uri.Builder builder = BASE_URI.buildUpon();
      builder.appendPath(PATH_SEARCH)
          .appendQueryParameter(QUERY_PARAM_SEARCH_TERM_KEY, QUERY_PARAM_SEARCH_TERM_VALUE_NAME)
          .appendQueryParameter(QUERY_PARAM_SEARCH_VALUE_KEY, dataSetName);

      Uri seriesIdRequestUri = builder.build();
      String requestUrl = seriesIdRequestUri.toString();
      emitter.onSuccess(requestUrl);
    });
  }

  @NonNull
  @Override
  public Single<String> getData(@NonNull String requestURL) {
    return Single.create(emitter -> {
      // Create URL object
      URL url;
      url = new URL(requestURL);
      String jsonResponse = makeHttpRequest(url);
      emitter.onSuccess(jsonResponse);
    });
  }

  /**
   * Generate an exact gasoline data set name base on region, required by
   * {@link #createSeriesIdRequestURL(Address)}
   * <p>
   * see https://www.eia.gov/opendata/qb.php?category=240839&sdid=PET.EMM_EPM0_PTE_SCA_DPG.W
   * ex. California All Grades All Formulations Retail Gasoline Prices, Weekly
   * <p>
   * mRegionName: East Coast (as Determined by the PADD1 Region Code)
   * mGrade:  Regular, MidGrade, Premium, All Grades
   * mFormulation: All Formulations, Reformulated , Conventional
   * mCommodityType: Retail Gasoline Prices,
   * mUpdateFreq: Weekly, Monthly, Annually
   *
   * @param address
   * @return a corresponding data set's name in service api
   */
  @NonNull
  private String getDataSetName(@NonNull Address address) {
    // Request data set name concat elements
    String mRegionName = mMatcherStrategy.matchRegion(address); // ex. California
    String mGrade = "All Grades";
    String mFormulation = "All Formulations";
    String mMarketType = "Retail Gasoline Prices";
    String mUpdateFreq = ", Weekly";

    String[] dataSetNameFrag = new String[]{
        mRegionName,
        mGrade,
        mFormulation,
        mMarketType,
        mUpdateFreq
    };

    return Joiner.on(" ").skipNulls().join(dataSetNameFrag);
  }

  /**
   * Make an HTTP request to the given URL and return a String as the response.
   * This establishes the necessary http connection to the server
   * This method takes the argument url
   * This methods makes calls to {@link #readFromStream(InputStream)}
   */
  @NonNull
  @RequiresPermission(Manifest.permission.INTERNET)
  private String makeHttpRequest(@NonNull URL url) throws IOException, NetworkErrorException {
    String jsonResponse = "";
    HttpURLConnection urlConnection = null;
    InputStream inputStream = null;

    try {
      urlConnection = (HttpURLConnection) url.openConnection();
      urlConnection.setReadTimeout(10000 /* milliseconds */);
      urlConnection.setConnectTimeout(15000 /* milliseconds */);
      urlConnection.setRequestMethod("GET");
      urlConnection.connect();

      // If the request was successful (response code 200),
      // then read the input stream and parse the response.
      if (urlConnection.getResponseCode() == 200) {
        inputStream = urlConnection.getInputStream();
        jsonResponse = readFromStream(inputStream);
      } else {
        throw new NetworkErrorException("Error response code: "
            + urlConnection.getResponseCode());
      }
    } finally {
      if (urlConnection != null) {
        urlConnection.disconnect();
      }
      if (inputStream != null) {
        inputStream.close();
      }
    }
    return jsonResponse;
  }

  /**
   * Convert the {@link InputStream} into a String which contains the
   * whole JSON response from the server.
   */
  @NonNull
  private String readFromStream(@Nullable InputStream inputStream) throws IOException {
    StringBuilder output = new StringBuilder();
    if (inputStream != null) {
      InputStreamReader inputStreamReader = new InputStreamReader(inputStream,
          Charset.forName("UTF-8"));
      BufferedReader reader = new BufferedReader(inputStreamReader);
      String line = reader.readLine();
      while (line != null) {
        output.append(line);
        line = reader.readLine();
      }
    }
    return output.toString();
  }
}
