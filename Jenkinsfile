/**
 * CI pipeline for fieldwork-ops.
 *
 * What it does:
 *   1. Checks out the repo.
 *   2. Runs backend unit tests (surefire; *IntegrationTest excluded by pom.xml).
 *   3. Runs backend integration tests + packages the jar (failsafe; *IntegrationTest
 *      run against Testcontainers-managed PostgreSQL — the agent needs a Docker
 *      daemon for this stage).
 *   4. Builds and tests the React frontend.
 *   5. Builds the backend Docker image.
 *   6. Archives the jar, test reports, and frontend bundle.
 *
 * What it deliberately does NOT do: publish to a container registry or deploy
 * anywhere. There is no registry configured and no deployment target; those
 * stages get added when a target exists.
 */
pipeline {
    agent any

    tools {
        // Tool names must match the Jenkins controller's configured installations.
        jdk 'jdk-17'
        maven 'maven-3.9'
        nodejs 'node-20'
    }

    environment {
        // Keep the local build hermetic; CI never touches a developer's database.
        MAVEN_OPTS = '-Dmaven.repo.local=.m2'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Backend unit tests') {
            steps {
                dir('backend') {
                    // -B: batch mode (no interactive prompts in CI).
                    // Surefire excludes **/*IntegrationTest.java (see pom.xml);
                    // those run under failsafe in the next stage.
                    sh 'mvn -B test'
                }
            }
            post {
                always {
                    junit 'backend/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Backend integration tests & package') {
            steps {
                dir('backend') {
                    // mvn verify: unit tests + Testcontainers-based integration
                    // tests (*IntegrationTest, run by failsafe) + jar packaging.
                    // Integration tests spin up their own PostgreSQL via
                    // Testcontainers, so they need a Docker daemon on this agent
                    // but never touch a shared database.
                    //
                    // If the agent has no network access to Maven Central, add -o
                    // (offline) once the local repository cache is warm:
                    //   sh 'mvn -B -o verify'
                    sh 'mvn -B verify'
                }
            }
            post {
                always {
                    junit 'backend/target/failsafe-reports/*.xml'
                }
            }
        }

        stage('Frontend build & test') {
            steps {
                dir('frontend') {
                    sh 'npm ci'
                    sh 'npm test'
                    sh 'npm run build'
                }
            }
        }

        stage('Docker build') {
            steps {
                sh 'docker build -f backend/Dockerfile -t fieldwork-ops:${BUILD_NUMBER} .'
            }
        }
    }

    post {
        always {
            // Artifacts worth keeping per build.
            archiveArtifacts artifacts: 'backend/target/fieldwork-ops-*.jar', fingerprint: true
            archiveArtifacts artifacts: 'frontend/dist/**', allowEmptyArchive: true
            junit 'backend/target/surefire-reports/*.xml', allowEmptyResults: true
            cleanWs()
        }
    }
}
