pipeline {
    agent any

    tools {
        // Tool names must match the Jenkins controller's configured installations.
        jdk 'jdk-17'
        maven 'maven-3.9'
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

        stage('Build') {
            steps {
                dir('backend') {
                    sh 'mvn -B -DskipTests package'
                }
            }
        }

        stage('Unit Tests') {
            steps {
                dir('backend') {
                    sh 'mvn -B test'
                }
            }
            post {
                always {
                    junit 'backend/target/surefire-reports/*.xml'
                }
            }
        }

        // Phase 9: integration tests run against Testcontainers-managed Postgres.
        // stage('Integration Tests') {
        //     steps {
        //         dir('backend') {
        //             sh 'mvn -B -Dtest=IT* verify'
        //         }
        //     }
        // }

        stage('Docker Build') {
            steps {
                sh 'docker build -f backend/Dockerfile -t fieldwork-ops:${BUILD_NUMBER} .'
            }
        }

        // Later phases: tag + push to a registry and deploy. Deliberately absent
        // until there is something worth deploying.
        // stage('Publish & Deploy') { ... }
    }

    post {
        always {
            cleanWs()
        }
    }
}
