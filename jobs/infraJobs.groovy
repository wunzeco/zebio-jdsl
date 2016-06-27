/*
    Note: To access Jenkins env variables (such as WORKSPACE, BUILD_NUMBER)
           from within DSL scripts just wrap them in '${}'.
*/
if ( !Object.metaClass.hasProperty('BUILD_NUMBER') ) {
    BUILD_NUMBER = '1.0'
} 

print "Build number is => ${BUILD_NUMBER}"

/*
    service/component infra build/update/delete/config-upload job
*/

def svc = 'priority'
def rakeScriptsRepo = 'infra-rake-tf-build'
def productDslRepo = 'infra-priority-stack'

[ 'create', 'destroy', 'update', 'upload' ].each {
    def cmd = it

    job("${svc}-infra-${cmd}") {
        parameters {
            choiceParam('DC_ENVIRONMENT', ['dev', 'test', 'stage', 'prod'],
                        'Product environment to build')
            stringParam('AWS_DEFAULT_REGION', defaultValue = 'eu-west-1', 
                        description = 'AWS Default Region')
        }         
        multiscm {
            git {
                remote {
                    github("o2-priority/${rakeScriptsRepo}", "ssh")
                    credentials("priority-ci-user-git-creds-id")
                }
                branch('master')
                relativeTargetDir(rakeScriptsRepo)
            }
            git {
                remote {
                    github("o2-priority/${productDslRepo}", "ssh")
                    credentials("priority-ci-user-git-creds-id")
                }
                branch('master')
                relativeTargetDir("${productDslRepo}")
            }
        }
        steps {
            environmentVariables {
                env('AWS_DEFAULT_REGION', '\$AWS_DEFAULT_REGION')
                env('DC_PRODUCT', 'priority')
                env('DC_CATEGORY', 'priority')
                env('DC_BUCKET_NAME', 'priority-infra')
                env('DC_BUILD_NUMBER', '\$BUILD_NUMBER')
                env('DC_CREATE_IF_NOT_EXIST', 'true')
                env('DC_ENVIRONMENT', '\$DC_ENVIRONMENT')
                env('DC_INFRA_SRC_PATH', "\$WORKSPACE/${productDslRepo}")
                env('DC_RAKE_SCRIPTS_PATH', "\$WORKSPACE/${rakeScriptsRepo}")
                env('DC_TF_ROOT_SUBDIR', 'terraform/providers/aws/\${AWS_DEFAULT_REGION}_\${DC_ENVIRONMENT}')
            }
            shell("set +x\n" +
                    "PATH=\$PATH:/usr/local/terraform/bin\n" + 
                    "export AWS_ACCESS_KEY_ID=\$AWS_ACCESS_KEY_ID\n" + 
                    "export AWS_SECRET_ACCESS_KEY=\$AWS_SECRET_ACCESS_KEY\n" + 
                    "cd \$DC_RAKE_SCRIPTS_PATH\n" + 
                    "gem install bundler && bundle install && rake tf:${cmd}")
        }
        wrappers {
            rvm('2.2.3@rake-tf-build')
            colorizeOutput()
            credentialsBinding {
                string('AWS_ACCESS_KEY_ID', 'priority-infra-aws-access-key-id-creds-id')
                string('AWS_SECRET_ACCESS_KEY', 'priority-infra-aws-secret-access-key-creds-id')
            }
        }
    }
}


/*
    service infra code validate job
*/
job("${svc}-infra-validate") {
    parameters {
        stringParam('AWS_DEFAULT_REGION', defaultValue = 'eu-west-1', description = 'AWS Default Region')
    }         
    multiscm {
        git {
            remote {
                github("o2-priority/${rakeScriptsRepo}", 'ssh')
                credentials("priority-ci-user-git-creds-id")
            }
            branch('master')
            relativeTargetDir(rakeScriptsRepo)
        }
        git {
            remote {
                github("o2-priority/${productDslRepo}", 'ssh')
                credentials("priority-ci-user-git-creds-id")
            }
            branch('master')
            relativeTargetDir("${productDslRepo}")
        }
    }
    triggers {
        scm 'H/2 * * * *'
    }
    steps {
        environmentVariables {
            env('AWS_DEFAULT_REGION', '\$AWS_DEFAULT_REGION')
            env('DC_PRODUCT', 'priority')
            env('DC_CATEGORY', 'priority')
            env('DC_BUCKET_NAME', 'priority-infra')
            env('DC_BUILD_NUMBER', '\$BUILD_NUMBER')
            env('DC_CREATE_IF_NOT_EXIST', 'true')
            env('DC_ENVIRONMENT', 'dev')
            env('DC_INFRA_SRC_PATH', "\$WORKSPACE/${productDslRepo}")
            env('DC_RAKE_SCRIPTS_PATH', "\$WORKSPACE/${rakeScriptsRepo}")
            env('DC_TF_ROOT_SUBDIR', 'terraform/providers/aws/\${AWS_DEFAULT_REGION}_\${DC_ENVIRONMENT}')
        }
        shell("set +x\n" +
                "PATH=\$PATH:/usr/local/terraform/bin\n" + 
                "export AWS_ACCESS_KEY_ID=\$AWS_ACCESS_KEY_ID\n" + 
                "export AWS_SECRET_ACCESS_KEY=\$AWS_SECRET_ACCESS_KEY\n" + 
                "cd \$DC_RAKE_SCRIPTS_PATH\n" + 
                "gem install bundler && bundle install && rake tf:validate")
    }
    wrappers {
        rvm('2.2.3@rake-tf-build')
        colorizeOutput()
        credentialsBinding {
            string('AWS_ACCESS_KEY_ID', 'priority-infra-aws-access-key-id-creds-id')
            string('AWS_SECRET_ACCESS_KEY', 'priority-infra-aws-secret-access-key-creds-id')
        }
    }
    publishers {
        downstreamParameterized {
            trigger("${svc}-infra-deploy") {
                condition('SUCCESS')
                parameters {
                    currentBuild()
                    predefinedProp('DC_ENVIRONMENT', 'dev')
                    predefinedProp('AWS_DEFAULT_REGION', 'eu-west-1')
                }
            }
        }
    }
}


/*
    service infra ansiblize job
*/
job("${svc}-infra-ansiblize") {
    parameters {
        choiceParam('DC_ENVIRONMENT', ['dev', 'test', 'stage', 'prod'],
                    'Product environment to build')
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
        environmentVariables {
            env('DC_ENVIRONMENT', '\$DC_ENVIRONMENT')
            env('DC_INFRA_SRC_PATH', "\$WORKSPACE/${productDslRepo}")
        }
        shell("set +x\n" +
                "PLAYBOOKS='playbooks/mongodb.yml playbooks/cassandra.yml playbooks/kong.yml'\n" +
                "PLAYBOOKS=\"\$PLAYBOOKS playbooks/consul.yml playbooks/smanager.yml playbooks/snode.yml\"\n" +
                "PLAYBOOKS=\"\$PLAYBOOKS playbooks/sweb.yml\"\n" +
                "cd \$WORKSPACE/${productDslRepo}/ansible\n" + 
                "ansible-galaxy install -r requirements.yml -f -p galaxy_roles/\n" +
                "ansible-playbook -i environments/\$DC_ENVIRONMENT/inventory \$PLAYBOOKS")
    }
    wrappers {
        colorizeOutput()
    }
}


/*
    service infra deploy job
*/
job("${svc}-infra-deploy") {
    parameters {
        choiceParam('DC_ENVIRONMENT', ['dev', 'test', 'stage', 'prod'],
                    'Product environment to build')
        stringParam('AWS_DEFAULT_REGION', defaultValue = 'eu-west-1', description = 'AWS Default Region')
    }         
    steps {
        downstreamParameterized {
            trigger("${svc}-infra-upload") {
                block {
                    buildStepFailure('FAILURE')
                    failure('FAILURE')
                    unstable('UNSTABLE')
                }
                parameters {
                    currentBuild()
                }
            }
            trigger("${svc}-infra-update") {
                block {
                    buildStepFailure('FAILURE')
                    failure('FAILURE')
                    unstable('UNSTABLE')
                }
                parameters {
                    currentBuild()
                }
            }
            trigger("${svc}-infra-ansiblize") {
                block {
                    buildStepFailure('FAILURE')
                    failure('FAILURE')
                    unstable('UNSTABLE')
                }
                parameters {
                    currentBuild()
                }
            }
        }
    }
}
