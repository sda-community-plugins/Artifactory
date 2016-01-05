import groovy.json.JsonSlurper
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

public class TestArtifactoryHelper {

    private String username;
    private String password;
    private String serverURL;
    private String authToken;

    private static final ARTIFACT_FILE_HASH_ALGORITHM = "sha1";

    public TestArtifactoryHelper(String username, String password, String serverURL) {
        this.username = username;
        this.password = password;
        if (serverURL.endsWith("/")) {
            this.serverURL = serverURL;
        } else {
            this.serverURL = serverURL + "/";
        }
        String creds = username+':'+password;
        this.authToken = "Basic " + creds.bytes.encodeBase64().toString()
    }


    public void verifyHash (File fileToVerify, storedDigest) {
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


    public File downloadFileFromRepo(String url, String checkHash) {
        HttpGet get = new HttpGet(this.serverURL + "api/storage/" + url);
        HttpResponse response = executeHttpRequest(get, HttpStatus.SC_OK, null);
        def jsonString = EntityUtils.toString(response.getEntity());
        JSONObject jsonResponse = new JSONObject(new JSONTokener(jsonString));
        def checksumMap = jsonResponse.getJSONObject("checksums")
        def downloadUrl =  jsonResponse.getString("downloadUri")
        System.out.println("Downloading: " + downloadUrl);
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
                System.out.println("Verification for file : " + artifactFile + " : succeeded!");
            }
            System.out.println("Created file : " + artifactFile.toString())
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

        println ">>>Sending request: ${request}"
        if (body != null) "\n>>>Body contents:\n${body}";
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

        println ">>>Received the response:"
        println response.getStatusLine();
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
        println "Request failed : " + response.getStatusLine();
        String responseString = new BasicResponseHandler().handleResponse(response);
        println "${responseString}";
        System.exit(1);
    }

    /**
     * Prints the key and value for a server instance property
     * @param key The key for the object being printed
     * @param val The value for the object being printed
     */
    private void printProps(String key, String val) {
        println "Key: " + key + " has value: " + val;
    }
}

TestArtifactoryHelper artifactoryTest = new TestArtifactoryHelper("admin", "password", "http://localhost:8081/artifactory")
def artifactUri = "libs-release-local/com/google/code/gson/2.5/gson-2.5.jar"
def checkHash = true
File artifactFile = artifactoryTest.downloadFileFromRepo(artifactUri, checkHash.toString());
if (artifactFile == null) {
    throw new Exception("Failed to download artifact : " + artifact);
}
