/*
    Note: To access Jenkins env variables (such as WORKSPACE, BUILD_NUMBER)
           from within DSL scripts just wrap them in '${}'.
*/
if ( !Object.metaClass.hasProperty('BUILD_NUMBER') ) {
    BUILD_NUMBER = '1.0'
} 

print "Build number is => ${BUILD_NUMBER}"

def svc = 'priority-web'
def rakeScriptsRepo = 'infra-rake-tf-build'
def productDslRepo = 'infra-priority-stack'

/*
    service/component build job
*/
job("${svc}-build") {
    scm {
        git {
            remote {
                github("o2-priority/app-web", "ssh")
                credentials("priority-ci-user-git-creds-id")
            }
            branch('ci-pipeline')
        }
    }
    triggers {
        scm('H/2 * * * *')
    }
    steps {
        shell ( "cat >\$WORKSPACE/build.properties <<EOF\n" +
                "ARTIFACT_VERSION=\$(python artifact_version.py nexus -l http://nexus.equalexperts.com \\\n" +
                "                       -G uk.co.o2.priority -A priority-web-service -R priority-build-artifacts -N)\nEOF" )
        environmentVariables {
            propertiesFile('build.properties')
        }
        gradle 'clean startManagedMongoDb startRedis npm_test distTarGz publish'
    }
    wrappers {
        buildName('#${BUILD_NUMBER}-v${ENV,var="ARTIFACT_VERSION"}')
        colorizeOutput()
        credentialsBinding {
            usernamePassword('NEXUS_LOGIN', 'priority-nexus-login-creds-id')
        }
    }
    publishers {
        //archiveJunit '**/test-results/*.xml'
        archiveArtifacts {
            pattern("**/build/docker/Dockerfile")
            pattern("**/build/distributions/*.tar.gz")
            onlyIfSuccessful()
        }
        //downstreamParameterized {
        //    trigger("${svc}-deploy") {
        //        condition('SUCCESS')
        //        parameters {
        //            currentBuild()
        //            predefinedProp('DC_ENVIRONMENT', 'dev')
        //            predefinedProp('APP_NAME', "${svc}-service")
        //            predefinedProp('APP_VERSION', "\$ARTIFACT_VERSION")
        //        }
        //    }
        //}
    }
}

/*
    svc deploy job
*/
job("${svc}-deploy") {
    parameters {
        choiceParam('DC_ENVIRONMENT', ['dev', 'test', 'stage', 'prod'],
                    'Product environment to build')
        stringParam('APP_NAME',     defaultValue = 'priority-web-service', 
                    description = 'App Name')
        stringParam('APP_VERSION',  defaultValue = '22', 
                    description = 'App Version')
    }         
    multiscm {
        git {
            remote {
                github("o2-priority/${productDslRepo}", 'ssh')
                credentials("priority-ci-user-git-creds-id")
            }
            branch('master')
            relativeTargetDir("${productDslRepo}")
        }
    }
    steps {
        shell(
            """
            EXTRA_VARS=\"dockerize_app_name=\$APP_NAME dockerize_app_version=\$APP_VERSION\"
            EXTRA_VARS=\"\$EXTRA_VARS dockerize_app_conf_file=/opt/app/conf/\$DC_ENVIRONMENT.yml\"
            EXTRA_VARS=\"\$EXTRA_VARS nginx_docker_container_port=5000\"
            cd $productDslRepo/ansible
            ansible-galaxy install -r requirements.yml -f -p galaxy_roles/
            ansible-playbook -i environments/\$DC_ENVIRONMENT/inventory playbooks/dockerize.yml -e \"\$EXTRA_VARS\"
            """.stripIndent()
             )
        shell(
            """
            EXTRA_VARS=\"kong_api_obj_name=${svc} kong_api_obj_request_path='/${svc}-service'\"
            EXTRA_VARS=\"\$EXTRA_VARS kong_api_obj_upstream_url='http://sweb.\$DC_ENVIRONMENT.priority-infra.co.uk/${svc}-service/${svc}'\"
            EXTRA_VARS=\"\$EXTRA_VARS kong_api_obj_preserve_host=false kong_api_obj_strip_request_path=true\"
            cd $productDslRepo/ansible
            ansible-playbook -i environments/\$DC_ENVIRONMENT/inventory playbooks/kong_api_obj.yml -e \"\$EXTRA_VARS\"
            """.stripIndent()
                )
    }
    wrappers {
        colorizeOutput()
    }
}

/*
    svc functional test job
*/
job("${svc}-test-functional") {
    scm {
        git {
            remote {
                github("o2-priority/app-web", "ssh")
                credentials("priority-ci-user-git-creds-id")
            }
            branch('ci-pipeline')
        }
    }
    triggers {
        upstream("${svc}-deploy")
    }
    steps {
        shell("echo 'Run functional test'")
    }
}

/*
    svc performance test job
*/
job("${svc}-test-performance") {
    scm {
        git {
            remote {
                github("o2-priority/app-web", "ssh")
                credentials("priority-ci-user-git-creds-id")
            }
            branch('ci-pipeline')
        }
    }
    triggers {
        upstream("${svc}-test-functional")
    }
    steps {
        shell("echo 'Run performance test'")
    }
}
