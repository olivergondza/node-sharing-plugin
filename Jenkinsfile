timestamps {
    node('docker') {
        /* clean out the workspace just to be safe */
        deleteDir()

        /* Grab our source for this build */
        checkout scm

        /* Make sure our directory is there, if Docker creates it, it gets owned by 'root' */
        sh 'mkdir -p $HOME/.m2'

        /* Share docker socket to run sibling container and maven local repo */
        String containerArgs = '-v /var/run/docker.sock:/var/run/docker.sock -v $HOME/.m2:/var/maven/.m2'

        stage('Build/Test Host Configurator') {
            dir('foreman-host-configurator') {

                docker.image('maven:3.3-jdk-8').inside(containerArgs) {

                    sh 'mvn -B -U -e -Duser.home=/var/maven -Dmaven.test.failure.ignore=true clean install -DskipTests'

                    // let foreman-host-configurator build jar
                    sh 'rm -f target/foreman-host-configurator.jar'
                    def r = sh script: './foreman-host-configurator --help', returnStatus: true
                    if (r != 2) {
                        error('failed to run foreman-host-configurator --help')
                    }
                    // now let it use artifact
                    sh 'mv target/foreman-host-configurator.jar foreman-host-configurator.jar'
                    r = sh script: './foreman-host-configurator --help', returnStatus: true
                    if (r != 2) {
                        error('failed to run foreman-host-configurator --help')
                    }
                }
                def uid = sh(script: 'id -u', returnStdout: true).trim()
                def gid = sh(script: 'id -g', returnStdout: true).trim()
                String buildArgs = "--build-arg=uid=${uid} --build-arg=gid=${gid} src/test/resources/ath-container"
                docker.build('jenkins/ath', buildArgs)
                docker.image('jenkins/ath').inside(containerArgs) {
                    sh 'mvn clean test -Dmaven.test.failure.ignore=true -Duser.home=/var/maven -B'
                }

                junit 'target/surefire-reports/*.xml'
                archive 'foreman-host-configurator'
                archive 'foreman-host-configurator.jar'
            }
        }

        stage('Test Plugin') {
            dir('foreman-node-sharing-plugin') {
                docker.image('jenkins/ath').inside(containerArgs) {
                    sh '''
                    eval $(./vnc.sh 2> /dev/null)
                    mvn test -Dmaven.test.failure.ignore=true -Duser.home=/var/maven -DforkCount=1 -B
                    '''
                }

                junit 'target/surefire-reports/*.xml'
                archive 'target/**/foreman-*.hpi'
                archive 'target/diagnostics/**'
            }
        }
    }
}