#!/usr/bin/env groovy
// the "!#/usr/bin... is just to to help IDEs, GitHub diffs, etc properly detect the language and do syntax highlighting for you.
// thx to https://github.com/jenkinsci/pipeline-examples/blob/master/docs/BEST_PRACTICES.md

// note that we set a default version for this library in jenkins, so we don't have to specify it here
@Library('misc')
import de.metas.jenkins.DockerConf
import de.metas.jenkins.Misc
import de.metas.jenkins.MvnConf

// thx to http://stackoverflow.com/a/36949007/1012103 with respect to the paramters
properties([
	parameters([
		string(defaultValue: '',
			description: '''If this job is invoked via an updstream build job, then that job can provide either its branch or the respective <code>MF_UPSTREAM_BRANCH</code> that was passed to it.<br>
This build will then attempt to use maven dependencies from that branch, and it will sets its own name to reflect the given value.
<p>
So if this is a "master" build, but it was invoked by a "feature-branch" build then this build will try to get the feature-branch\'s build artifacts annd will set its
<code>currentBuild.displayname</code> and <code>currentBuild.description</code> to make it obvious that the build contains code from the feature branch.''',
			name: 'MF_UPSTREAM_BRANCH'),

		string(defaultValue: '',
			description: 'Version of the metasfresh "main" code we shall use when resolving dependencies. Leave empty and this build will use the latest.',
			name: 'MF_UPSTREAM_VERSION'),

		booleanParam(defaultValue: true, description: '''Set to true if this build shall trigger "endcustomer" builds.<br>Set to false if this build is called from elsewhere and the orchestrating also takes place elsewhere.''',
			name: 'MF_TRIGGER_DOWNSTREAM_BUILDS'),

		booleanParam(defaultValue: false,
			name: 'MF_MF_SKIP_UNIT_TESTS')
	]),
	pipelineTriggers([]),
	buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '20')) // keep the last 20 builds
])

// set nodejs version defined in tool name of NodeJS installations located in Jenkins global plugins
final NODEJS_TOOL_NAME="nodejs-11"
echo "Setting NODEJS_TOOL_NAME=$NODEJS_TOOL_NAME"

timestamps
{
	final String MF_UPSTREAM_BRANCH = params.MF_UPSTREAM_BRANCH ?: env.BRANCH_NAME
	echo "params.MF_UPSTREAM_BRANCH=${params.MF_UPSTREAM_BRANCH}; env.BRANCH_NAME=${env.BRANCH_NAME}; => MF_UPSTREAM_BRANCH=${MF_UPSTREAM_BRANCH}"

	// https://github.com/metasfresh/metasfresh/issues/2110 make version/build infos more transparent
	final String MF_VERSION = retrieveArtifactVersion(MF_UPSTREAM_BRANCH, env.BUILD_NUMBER)
	currentBuild.displayName = "artifact-version ${MF_VERSION}";

	String BUILD_GIT_SHA1 = "NOT_YET_SET" // will be set when we check out

node('agent && linux') // shall only run on a jenkins agent with linux
{

final String BUILD_ARTIFACT_URL

stage('Set versions and build metasfresh-webui-frontend')
{
	final def scmVars = checkout scm
	BUILD_GIT_SHA1 = scmVars.GIT_COMMIT
	sh 'git clean -d --force -x' // clean the workspace


	sh "if [ -d ~/.npm ]; then rm -r ~/.npm; fi" // make sure the .npm folder isn't there. it caused us problems in the past when it contained "stale files".

	def nodeHome = tool name: "$NODEJS_TOOL_NAME"
	env.PATH = "${nodeHome}/bin:${env.PATH}"

	sh 'yarn install'
	sh 'yarn lint --quiet'
						
	sh "yarn add jest jest-junit --dev"
	if(params.MF_MF_SKIP_UNIT_TESTS)
	{
		echo "params.MF_MF_SKIP_UNIT_TESTS=${params.MF_MF_SKIP_UNIT_TESTS}, so we skip the jest unit tests."
	}
	else 
	{
		sh 'yarn test --ci --reporters="default" --reporters="jest-junit"'
		junit 'junit.xml'
	}
				
	sh "webpack --config webpack.prod.js --bail --display-error-details"

	// https://github.com/metasfresh/metasfresh-webui-frontend/issues/292
	// add a file info.json whose shall look similar to the info which spring-boot provides unter the /info URL
	final version_info_json = """{
  \"build\": {
	\"releaseVersion\": \"${MF_VERSION}\",
    \"jenkinsBuildUrl\": \"${env.BUILD_URL}\",
    \"jenkinsBuildNo\": \"${env.BUILD_NUMBER}\",
    \"jenkinsJobName\": \"${env.JOB_NAME}\",
    \"jenkinsBuildTag\": \"${env.BUILD_TAG}\"
    \"gitSHA1\": \"${BUILD_GIT_SHA1}\"
  }
}""";
	writeFile encoding: 'UTF-8', file: 'dist/info.json', text: version_info_json;

	sh "tar cvzf webui-dist-${MF_VERSION}.tar.gz dist"

	// upload our results to the maven repo

    configFileProvider([configFile(fileId: 'metasfresh-global-maven-settings', replaceTokens: true, variable: 'MAVEN_SETTINGS')])
    {
		// create our config instance to be used further on
		final MvnConf mvnConf = new MvnConf(
			'pom.xml', // pomFile
			MAVEN_SETTINGS, // settingsFile
			"mvn-${MF_UPSTREAM_BRANCH}".replace("/", "-"), // mvnRepoName
			'https://repo.metasfresh.com' // mvnRepoBaseURL - for resolve and deploy
		)
		echo "mvnConf=${mvnConf.toString()}"
		nexusCreateRepoIfNotExists mvnConf.mvnDeployRepoBaseURL, mvnConf.mvnRepoName
        withMaven(jdk: 'java-8', maven: 'maven-3.5.0', mavenLocalRepo: '.repository')
        {
			sh "mvn --settings ${mvnConf.settingsFile} ${mvnConf.resolveParams} -Dfile=webui-dist-${MF_VERSION}.tar.gz -Durl=${mvnConf.deployRepoURL} -DrepositoryId=${mvnConf.MF_MAVEN_REPO_ID} -DgroupId=de.metas.ui.web -DartifactId=metasfresh-webui-frontend -Dversion=${MF_VERSION} -Dpackaging=tar.gz -DgeneratePom=true org.apache.maven.plugins:maven-deploy-plugin:2.7:deploy-file"

			final misc = new de.metas.jenkins.Misc()
			BUILD_ARTIFACT_URL="${mvnConf.deployRepoURL}/de/metas/ui/web/metasfresh-webui-frontend/${misc.urlEncode(MF_VERSION)}/metasfresh-webui-frontend-${misc.urlEncode(MF_VERSION)}.tar.gz"
/*
			// IMPORTANT: we might parse this build description's href value in downstream builds!
			currentBuild.description="""artifacts (if not yet cleaned up)
<ul>
<li><a href=\"${BUILD_ARTIFACT_URL}\">metasfresh-webui-frontend-${MF_VERSION}.tar.gz</a></li>
</ul>""";
*/
		} // withMaven
	} // configFileProvider

	// gh #968:
	// set env variables which will be available to a possible upstream job that might have called us
	// all those env variables can be gotten from <buildResultInstance>.getBuildVariables()
	env.MF_VERSION=MF_VERSION
	env.BUILD_GIT_SHA1=BUILD_GIT_SHA1
} // stage

		final String publishedDockerImageName;

		stage('Build and push nginx docker image')
		{
			sh 'cp -r dist docker/nginx'

			final DockerConf materialDispoDockerConf = new DockerConf(
				'metasfresh-webui-dev', // artifactName
				MF_UPSTREAM_BRANCH, // branchName
				MF_VERSION, // versionSuffix
				'docker/nginx' // workDir
			);
			publishedDockerImageName = dockerBuildAndPush(materialDispoDockerConf)
		}

		final misc = new de.metas.jenkins.Misc()
		currentBuild.description="""This build's main artifacts (if not yet cleaned up) are
<ul>
<li><a href=\"${BUILD_ARTIFACT_URL}\">metasfresh-webui-frontend-${MF_VERSION}.tar.gz</a></li>
<li>a docker image with name <code>${publishedDockerImageName}</code>; Note that you can also use the tag <code>${misc.mkDockerTag(MF_UPSTREAM_BRANCH)}_LATEST</code></li>
</ul>"""

	// gh #968: set docker image name to be available to a possible upstream job that might have called us
	env.MF_DOCKER_IMAGE=publishedDockerImageName

 } // node

stage('Invoke downstream jobs')
{
	if(params.MF_TRIGGER_DOWNSTREAM_BUILDS)
	{
			final def misc = new de.metas.jenkins.Misc()
			final String metasfreshJobName = misc.getEffectiveDownStreamJobName('metasfresh', MF_UPSTREAM_BRANCH)
			final def metasfreshBuildResult = build job: metasfreshJobName,
				parameters: [
					string(name: 'MF_UPSTREAM_BRANCH', value: MF_UPSTREAM_BRANCH),
					string(name: 'MF_UPSTREAM_BUILDNO', value: env.BUILD_NUMBER),
					string(name: 'MF_UPSTREAM_VERSION', value: MF_VERSION),
					string(name: 'MF_UPSTREAM_JOBNAME', value: 'metasfresh-webui-frontend'),
					booleanParam(name: 'MF_TRIGGER_DOWNSTREAM_BUILDS', value: true), // metasfresh shall trigger the "-dist" jobs
					booleanParam(name: 'MF_SKIP_TO_DIST', value: true) // this param is only recognised by metasfresh
				],
				wait: true

			currentBuild.description="""${currentBuild.description}
	<p/>
	This build triggered the <b>metasfresh</b> jenkins job <a href="${metasfreshBuildResult.absoluteUrl}">${metasfreshBuildResult.displayName}</a>
	"""
	}
	else{
				echo "params.MF_TRIGGER_DOWNSTREAM_BUILDS=${params.MF_TRIGGER_DOWNSTREAM_BUILDS}, so we skip invoking the metasfresh downstream job"
	}
}
} // timestamps
