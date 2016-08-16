// If you want, you can define your seed job in the DSL and create it via the REST API.
// See README.md

/*
    _seed-microdc job definition
*/

job("_seed-microdc") {
    description("DSL seed job")
    scm {
        git {
            remote {
                github("wunzeco/zebio-jdsl")
                credentials("zebio-ci-user-git-creds-id")
            }
        }
    }
    triggers {
        scm 'H/2 * * * *'
    }
    steps {
        gradle 'clean test'
        dsl {
            external('jobs/**/*Jobs.groovy')
            additionalClasspath('src/main/groovy')
        }
        dsl { 
            external("**/*Pipeline.groovy")
        }
    }
    publishers {
        archiveJunit 'build/test-results/**/*.xml'
    }
}
