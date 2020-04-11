#!/usr/bin/env groovy
// the "!#/usr/bin... is just to to help IDEs, GitHub diffs, etc properly detect the language and do syntax highlighting for you.
// thx to https://github.com/jenkinsci/pipeline-examples/blob/master/docs/BEST_PRACTICES.md

// note that we set a default version for this library in jenkins, so we don't have to specify it here
@Library('misc')
import de.metas.jenkins.DockerConf
import de.metas.jenkins.MvnConf
import de.metas.jenkins.Misc


final String VERSIONS_PLUGIN = 'org.codehaus.mojo:versions-maven-plugin:2.5'
String BUILD_GIT_SHA1 = "NOT_YET_SET" // will be set when we check out

def build(final MvnConf mvnConf, final Map scmVars)
{
       
	stage('Build edi')
    {
		currentBuild.description= """${currentBuild.description}<p/>
			<h2>EDI</h2>
		"""
		def status = sh(returnStatus: true, script: "git diff --name-only ${scmVars.GIT_PREVIOUS_SUCCESSFUL_COMMIT} ${scmVars.GIT_COMMIT} . | grep .") // see if anything at all changed in this folder
			echo "status of git dif command=${status}"
			if(scmVars.GIT_COMMIT && scmVars.GIT_PREVIOUS_SUCCESSFUL_COMMIT && status != 0)
		{
			currentBuild.description= """${currentBuild.description}<p/>
			No changes happened in EDI.
			"""
			echo "no changes happened in edi; skip building edi";
			return;
		}
		
		// update the parent pom version
		mvnUpdateParentPomVersion mvnConf

		final String mavenUpdatePropertyParam
		
		// still update the property, but use the latest version
		mavenUpdatePropertyParam='-Dproperty=metasfresh.version'
		
		// update the metasfresh.version property. either to the latest version or to the given params.MF_UPSTREAM_VERSION.
		sh "mvn --settings ${mvnConf.settingsFile} --file ${mvnConf.pomFile} --batch-mode ${mvnConf.resolveParams} ${mavenUpdatePropertyParam} versions:update-property"

		// set the artifact version of everything below de.metas.esb/pom.xml
		sh "mvn --settings ${mvnConf.settingsFile} --file ${mvnConf.pomFile} --batch-mode -DallowSnapshots=false -DgenerateBackupPoms=true -DprocessDependencies=true -DprocessParent=true -DexcludeReactor=true -Dincludes=\"de.metas*:*\" ${mvnConf.resolveParams} -DnewVersion=${env.MF_VERSION} ${VERSIONS_PLUGIN}:set"

		// update the versions of metas dependencies that are external to the de.metas.esb reactor modules
		sh "mvn --settings ${mvnConf.settingsFile} --file ${mvnConf.pomFile} --batch-mode -DallowSnapshots=false -DgenerateBackupPoms=true -DprocessDependencies=true -DprocessParent=true -DexcludeReactor=true -Dincludes=\"de.metas*:*\" ${mvnConf.resolveParams} ${VERSIONS_PLUGIN}:use-latest-versions"

		// build and deploy
		// about -Dmetasfresh.assembly.descriptor.version: the versions plugin can't update the version of our shared assembly descriptor de.metas.assemblies. Therefore we need to provide the version from outside via this property
		// maven.test.failure.ignore=true: see metasfresh stage
		sh "mvn --settings ${mvnConf.settingsFile} --file ${mvnConf.pomFile} --batch-mode -Dmaven.test.failure.ignore=true -Dmetasfresh.assembly.descriptor.version=${env.MF_VERSION} ${mvnConf.resolveParams} ${mvnConf.deployParam} clean deploy"

		junit '**/target/surefire-reports/*.xml'

		jacoco()

		final DockerConf dockerConf = new DockerConf(
				'de-metas-edi-esb-camel', // artifactName
				env.BRANCH_NAME, // branchName
				env.MF_VERSION, // versionSuffix
				'./') // workDir
		final String publishedDockerImageName =	dockerBuildAndPush(dockerConf)

        currentBuild.description=""""""${currentBuild.description}<p/>
		This build's main artifact (if not yet cleaned up) is
<ul>
<li>a docker image with name <code>${publishedDockerImageName}</code>; Note that you can also use the tag <code>${env.BRANCH_NAME}_LATEST</code></li>
</ul>
You can run the docker image like this:<br>
<code>
docker run --rm\\<br/>
 -e "DEBUG_PORT=8792"\\<br/>
 -e "DEBUG_SUSPEND=n"\\<br/>
 -e "DEBUG_PRINT_BASH_CMDS=n"\\<br/>
 -e "SERVER_PORT=8184"\\<br/>
 -e "RABBITMQ_HOST=your.rabbitmq.host"\\<br/>
 -e "RABBITMQ_PORT=your.rabbitmq.port"\\<br/>
 -e "RABBITMQ_USER=your.rabbitmq.user"\\<br/>
 -e "RABBITMQ_PASSWORD=your.rabbitmq.password"\\<br/>
 ${publishedDockerImageName}
</code>
<p/>
"""
    } // stage
}

return this
