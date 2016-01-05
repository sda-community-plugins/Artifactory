package com.serena.air.plugin.artifactory;

import com.urbancode.air.AirPluginTool;

import org.apache.http.client.HttpClient
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.HttpStatus
import org.apache.http.HttpException;
import org.apache.http.entity.StringEntity;
import org.apache.http.HttpResponse
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils

import java.security.NoSuchAlgorithmException

import org.json.JSONObject;
import org.json.JSONTokener;
import org.json.JSONArray;

import com.urbancode.commons.fileutils.FileUtils
import com.urbancode.commons.fileutils.digest.DigestUtil
import org.apache.commons.lang.ObjectUtils

public class ArtifactoryHelper {

    private AirPluginTool pluginTool;
    private String username;
    private String password;
    private String serverURL;
    private String authToken;
    private def props;

    private static final ARTIFACT_FILE_HASH_ALGORITHM = "sha1";

    /**
     * Constructs an Artifactory Helper and creates an authentication header for REST calls
     * @params pluginTool The AirPluginTool containing all step properties
     */
    public ArtifactoryHelper(AirPluginTool pluginTool) {
        this.pluginTool = pluginTool;
        this.props = this.pluginTool.getStepProperties();
        if ((props['username'] == null) || (props['serverUrl'] == null))
            exitFailure("A username, password and server URL have not been provided.");
        this.username = props['username'];
        this.password = props['password'];
        if (props['serverUrl'].endsWith("/")) {
            this.serverURL = props['serverUrl'];
        } else {
            this.serverURL = props['serverUrl'] + "/";
        }
        String creds = this.username + ':' + this.password;
        this.authToken = "Basic " + creds.bytes.encodeBase64().toString();
    }

    //
    // public methods
    //

    public getServerURL() { return this.serverURL; }

    /**
     * Verify that the hash for the downloaded file is the same as stored in Artifactory
     * @param fileToVerify The file to verify has for
     * @param storedDigest The stored digest in artifactory
     */
    public void verifyHash(File fileToVerify, storedDigest) {
        if (storedDigest != null) {
            String computedDigest;
            try {
                computedDigest = DigestUtil.getHexDigest(fileToVerify, ARTIFACT_FILE_HASH_ALGORITHM);
                if (!ObjectUtils.equals(storedDigest, computedDigest)) {
                    throw new Exception("Artifact file verification of " + fileToVerify.getName() +
                            " failed. Expected digest of " + storedDigest + " but the downloaded file was " + computedDigest);
                }
            }
            catch (NoSuchAlgorithmException e) {
                throw new Exception("Algorithm to verify Maven remote artifacts not supported: " +
                        ARTIFACT_FILE_HASH_ALGORITHM);
            }
            catch (IOException e) {
                throw new Exception("Error verifying downloaded Maven remote artifacts: " +
                        e.getMessage(), e);
            }
        }
    }

    /**
     * Download a file from Artifactory
     * @param url The url of the artifact to download
     * @param checkHash whether to carry out checksum check on downloaded file
     * @return The downloaded File
     */
    public File downloadFile(String url, String checkHash) {
        HttpGet get = new HttpGet(url);
        HttpResponse response = executeHttpRequest(get, HttpStatus.SC_OK, null);
        def jsonString = EntityUtils.toString(response.getEntity());
        JSONObject jsonResponse = new JSONObject(new JSONTokener(jsonString));
        def checksumMap = jsonResponse.getJSONObject("checksums")
        def downloadUrl =  jsonResponse.getString("downloadUri")
        if (props['debug']) {
            println ">>> Downloading file ${downloadUrl} from Artifactory instance: ${this.serverURL}"
        }
        get = new HttpGet(downloadUrl.toString());
        response = executeHttpRequest(get, HttpStatus.SC_OK, null);
        int status = response.getStatusLine().getStatusCode();
        if (status == HttpStatus.SC_OK) {
            String tempFileSuffix = ".maven2";
            int extIndex = url.lastIndexOf(".");
            if (extIndex >= 0) {
                tempFileSuffix = url.substring(extIndex);
            }
            File artifactFile = File.createTempFile("maven2-", tempFileSuffix);
            FileUtils.writeInputToFile(response.getEntity().getContent(), artifactFile);
            if (checkHash && Boolean.valueOf(checkHash)) {
                //verify checksum
                verifyHash(artifactFile, checksumMap.sha1);
                if (props['debug']) {
                    println ">>> Verification for file ${artifactFile} succeeded"
                }
            }
            if (props['debug']) {
                println ">>> Created file " + artifactFile.toString()
            }
            return artifactFile;
        } else {
            throw new Exception("Exception downloading file : " + downloadUrl + "\nErrorCode : " + status.toString());
        }
    }

    /**
     * Executes the given HTTP request and checks for a correct response status
     * @param request The HttpRequest to execute
     * @param expectedStatus The response status that indicates a successful request
     * @param body The JSONObject containing the request body
     * @return A JSONObject containing the response to the HTTP request executed
     */
    private HttpResponse executeHttpRequest(Object request, int expectedStatus, JSONObject body) {
        // Make sure the required parameters are there
        if ((request == null) || (expectedStatus == null)) exitFailure("An error occurred executing the request.");

        if (props['debug']) {
            println ">>> Sending request: ${request}"
            if (body != null) println "\n>>> Body contents: ${body}";
        }

        HttpClient client = new DefaultHttpClient();
        request.setHeader("Authorization", this.authToken);
        if (body) {
            StringEntity input = new StringEntity(body.toString());
            input.setContentType("application/json");
            request.setEntity(input);
        }

        HttpResponse response;
        try {
            response = client.execute(request);
        } catch (HttpException e) {
            exitFailure("There was an error executing the request.");
        }

        if (!(response.getStatusLine().getStatusCode() == expectedStatus))
            httpFailure(response);

        if (props['debug']) {
            println ">>> Received the response: " + response.getStatusLine();
        }
        return response;
    }

    /**
     * Write an error message to console and exit on a fail status.
     * @param message The error message to write to the console.
     */
    private void exitFailure(String message) {
        println "${message}";
        System.exit(1);
    }

    /**
     * Write a HTTP error message to console and exit on a fail status.
     * @param message The error message to write to the console.
     */
    private void httpFailure(HttpResponse response) {
        println ">>> Request failed : " + response.getStatusLine();
        String responseString = new BasicResponseHandler().handleResponse(response);
        println "${responseString}";
        System.exit(1);
    }

    /**
     * Check if a string is null, empty, or all whitespace
     * @param str The string whose value to check
     */
    public boolean isEmpty(String str) {
        return (str == null) || str.trim().isEmpty();
    }

    //
    // private methods
    //


}