import com.urbancode.air.AirPluginTool

import com.serena.air.plugin.artifactory.ArtifactoryHelper

import groovy.json.JsonSlurper
import org.apache.http.HttpResponse
import org.apache.http.HttpStatus
import org.apache.http.client.methods.HttpGet
import org.apache.http.util.EntityUtils
import org.json.JSONObject
import org.json.JSONTokener

import com.urbancode.commons.util.IO
import com.urbancode.commons.fileutils.FileUtils

final def apTool = new AirPluginTool(args[0], args[1])
final def props = apTool.getStepProperties()
final def helper = new ArtifactoryHelper(apTool)
final def workDir = new File('.').canonicalFile
final def REPO_PATH_SEPARATOR = "/"

def repoName = props['repositoryName']
def artifacts = props['artifacts'].split('\n')
def checkHash = props['checkHash']

def exitVal = 0

def searchArtifacts = { searchUrl ->
    HttpGet get = new HttpGet(searchUrl)
    HttpResponse response = helper.executeHttpRequest(get, HttpStatus.SC_OK, null)
    int status = response.getStatusLine().getStatusCode()
    if (status == HttpStatus.SC_OK) {
        def artifactUris = []
        def jsonString = EntityUtils.toString(response.getEntity())
        JSONObject jsonResponse = new JSONObject(new JSONTokener(jsonString));
        def resultArr = jsonResponse.getJSONArray("results")
        for (int i = 0; i < resultArr.length(); i++) {
            artifactUris.add(resultArr.getJSONObject(i).getString("uri"))
        }
        return artifactUris;
    }
    else {
        throw new Exception("Exception searching: " + searchUrl + "\nErrorCode : " + status.toString())
    }
}

try {
    artifacts.each { artifact ->
        String filter = ''
        String[] attrs = artifact.split('/')
        String nugetVer = attrs[1]
        String nugetPackage = attrs[0]
        String searchUrl = helper.getServerURL() + 'api/search/prop?' +
                '&nuget.id=' + nugetPackage + '&nuget.version=' + nugetVer
        if (!helper.isEmpty(repoName)) {
            searchUrl = searchUrl + '&repos=' + repoName
        }

        def artifactUris = searchArtifacts(searchUrl)
        for (artifactUri in artifactUris) {
            println "Downloading nuget package ${artifactUri} from Artifactory"
            File artifactFile = helper.downloadFile(artifactUri, checkHash.toString())
            if (artifactFile == null) {
                throw new Exception("Failed to download package : " + artifact)
            }
            //copy the temp file to this directory with the file name
            String[] currFile = artifactUri.split(REPO_PATH_SEPARATOR)
            def filename = currFile[currFile.length - 1]
            File finalFile = new File(workDir, filename)
            println "Moving downloaded package to : " + finalFile.getAbsolutePath()
            IO.move(artifactFile, finalFile)
            artifactFile.delete()
        }
    }
}
catch (Exception e) {
    e.printStackTrace()
    exitVal = 1
}

System.exit(exitVal)