// CI/CD for the Account Upgrade platform, run by the Jenkins in jenkins/ (setup: jenkins/README.md).
//
//   checkout ─► backend tests (mvnw verify) ─► build images ─► Trivy scan ─► deploy to Kubernetes ─► end-to-end tests
//
// Deploy applies deploy/k8s one component at a time: postgres, kafka (ready) ─► schema Job (must succeed) ─► backend
// (rolled out) ─► frontend (rolled out). The end-to-end tests (account-upgrade-e2e, JUnit) then check the release
// through the cluster's own Service addresses. If the deployment or the tests fail, the images that were running
// before are deployed again and checked.
//
// Images are built with the cluster node's Docker engine (Rancher Desktop's moby) and tagged
// local/<service>:<commit>; Kubernetes runs them from there, so no registry is involved. Linux only: Jenkins runs in
// a container.

// Deployed in this order, each one with its own image.
def SERVICES = ['account-update-db-schema', 'account-upgrade-backend', 'account-upgrade-frontend']

pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        timeout(time: 60, unit: 'MINUTES')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '50'))
    }

    parameters {
        string(name: 'COMMIT', defaultValue: '',
               description: 'Deploy this commit instead of the latest one (redeploy or roll back). Must contain deploy/k8s.')
        booleanParam(name: 'SKIP_TESTS', defaultValue: false, description: 'Skip the backend tests (faster manual run).')
        booleanParam(name: 'SKIP_SCAN', defaultValue: false, description: 'Skip the vulnerability scan (faster manual run).')
    }

    environment {
        REGISTRY = 'local'
        NAMESPACE = 'account-upgrade'
        TRIVY_IMAGE = 'aquasec/trivy:0.74.0'
        // Older release images kept on the node for fast rollback; the rest are removed.
        KEEP_IMAGES = '5'
        WAIT = '300s'
        // Inside the cluster: the Services' DNS names, and kubectl with the pod's service account.
        UI_URL = 'http://account-upgrade-frontend.account-upgrade.svc.cluster.local:4200'
        API_URL = 'http://account-upgrade-backend.account-upgrade.svc.cluster.local:8080'
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    if (params.COMMIT?.trim()) {
                        if (!(params.COMMIT.trim() ==~ /[0-9a-fA-F]{7,40}/)) {
                            error("COMMIT must be a commit SHA, got '${params.COMMIT}'")
                        }
                        sh "git fetch --quiet origin && git checkout --quiet --force --detach ${params.COMMIT.trim()}"
                    }
                    sh 'git clean -ffdxq'
                    env.RELEASE = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.RELEASE.take(8)}"
                    sh 'git log -1 --format="%h %an, %ad: %s" --date=iso'
                }
            }
        }

        stage('Backend tests') {
            when { expression { !params.SKIP_TESTS } }
            steps {
                // Unit and integration tests (Testcontainers on the node's Docker engine), coverage gate, enforcer.
                dir('account-upgrade-backend') {
                    sh './mvnw -B -ntp verify'
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'account-upgrade-backend/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Build images') {
            steps {
                script {
                    // The frontend's unit tests and budgeted production build run inside its image build.
                    for (service in SERVICES) {
                        sh """docker build --progress=plain \
                                --label org.opencontainers.image.revision=${env.RELEASE} \
                                --label org.opencontainers.image.source=${env.GIT_URL} \
                                -t ${env.REGISTRY}/${service}:${env.RELEASE} ${service}"""
                    }
                }
            }
        }

        stage('Vulnerability scan') {
            when { expression { !params.SKIP_SCAN } }
            steps {
                script {
                    // No fixable CRITICAL vulnerability.
                    for (service in SERVICES) {
                        sh """docker run --rm \
                                -v /var/run/docker.sock:/var/run/docker.sock \
                                -v account-upgrade-trivy-cache:/root/.cache/ \
                                ${env.TRIVY_IMAGE} image --scanners vuln --severity CRITICAL --ignore-unfixed \
                                --exit-code 1 --no-progress ${env.REGISTRY}/${service}:${env.RELEASE}"""
                    }
                }
            }
        }

        stage('Deploy') {
            steps {
                script {
                    // Schema changes only go forward: rolling back re-runs the previous schema image, a no-op.
                    env.PREVIOUS_SCHEMA = runningImage('job', 'account-update-db-schema')
                    env.PREVIOUS_BACKEND = runningImage('deployment', 'account-upgrade-backend')
                    env.PREVIOUS_FRONTEND = runningImage('deployment', 'account-upgrade-frontend')
                    echo(env.PREVIOUS_BACKEND
                            ? "Currently running: ${env.PREVIOUS_BACKEND}, ${env.PREVIOUS_FRONTEND}"
                            : 'No release running yet (first deployment)')
                    env.DEPLOYING = 'true'
                    release(SERVICES.collect { "${env.REGISTRY}/${it}:${env.RELEASE}" })
                }
            }
        }

        stage('End-to-end tests') {
            steps {
                endToEndTests()
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'account-upgrade-e2e/target/surefire-reports/*.xml'
                }
            }
        }
    }

    post {
        success {
            script {
                currentBuild.description = "Deployed ${env.RELEASE.take(8)}"
                removeOldImages(SERVICES)
            }
        }
        failure {
            script {
                if (env.DEPLOYING == 'true') {
                    rollBack()
                }
            }
        }
    }
}

// The image a workload currently runs, or '' (not deployed yet).
def runningImage(String kind, String name) {
    return sh(script: "kubectl -n ${env.NAMESPACE} get ${kind} ${name} " +
                      "-o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true",
              returnStdout: true).trim()
}

// Rolls the given images (schema, backend, frontend) out in order with the manifests in base (a path from the
// workspace root), and waits for each component.
def release(List<String> images, String base = 'deploy/k8s/base') {
    // A kustomize overlay of the base pinning the images (the base only has placeholders).
    def names = ['account-update-db-schema', 'account-upgrade-backend', 'account-upgrade-frontend']
    def overlay = ['# Generated by the Jenkinsfile for one release. Not committed.',
                   'apiVersion: kustomize.config.k8s.io/v1beta1', 'kind: Kustomization',
                   'resources:', "  - ../../../${base}", 'images:']
    names.eachWithIndex { name, i ->
        def image = images[i]
        def split = image.lastIndexOf(':')
        overlay += ["  - name: ${name}", "    newName: ${image.substring(0, split)}", "    newTag: '${image.substring(split + 1)}'"]
    }
    writeFile file: 'deploy/k8s/release/kustomization.yaml', text: overlay.join('\n') + '\n'

    applyComponent('platform')
    ensureSecrets()
    applyComponent('postgres')
    applyComponent('kafka')
    sh 'kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout="$WAIT"'
    sh 'kubectl -n "$NAMESPACE" rollout status statefulset/kafka --timeout="$WAIT"'

    echo "Schema job: ${images[0]}"
    // A Job's pod template cannot change: replace the previous run's Job.
    sh 'kubectl -n "$NAMESPACE" delete job account-update-db-schema --ignore-not-found --wait=true'
    applyComponent('db-schema')
    waitForSchemaJob()

    echo "Backend: ${images[1]}"
    applyComponent('backend')
    sh 'kubectl -n "$NAMESPACE" rollout status deployment/account-upgrade-backend --timeout="$WAIT"'

    echo "Frontend: ${images[2]}"
    applyComponent('frontend')
    sh 'kubectl -n "$NAMESPACE" rollout status deployment/account-upgrade-frontend --timeout="$WAIT"'
}

def applyComponent(String component) {
    sh "kubectl -n \"\$NAMESPACE\" apply -k deploy/k8s/release --selector app.kubernetes.io/component=${component}"
}

// Created once with random values, then kept: PostgreSQL only reads its password when it initialises its volume, and
// a new JWT key would sign everyone out.
def ensureSecrets() {
    sh '''#!/bin/sh -e
        # No command tracing here: it would print the generated values.
        if kubectl -n "$NAMESPACE" get secret account-upgrade-secrets >/dev/null 2>&1; then exit 0; fi
        echo "Creating secret account-upgrade-secrets (random PostgreSQL password and JWT signing key)"
        kubectl -n "$NAMESPACE" create secret generic account-upgrade-secrets \
            --from-literal=postgres-password="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \\n')" \
            --from-literal=jwt-secret="$(head -c 48 /dev/urandom | base64 | tr -d '\\n')"
        kubectl -n "$NAMESPACE" label secret account-upgrade-secrets \
            app.kubernetes.io/part-of=account-upgrade app.kubernetes.io/component=platform
    '''
}

// Succeeded, failed, or still running after 5 minutes (the Job's own retries count as running).
def waitForSchemaJob() {
    sh '''#!/bin/sh -e
        for attempt in $(seq 1 150); do
            conditions=$(kubectl -n "$NAMESPACE" get job account-update-db-schema \
                -o jsonpath='{range .status.conditions[*]}{.type}={.status};{end}')
            case "$conditions" in
                *Complete=True*) echo "Schema job completed"; exit 0 ;;
                *Failed=True*) echo "The schema job failed:"
                               kubectl -n "$NAMESPACE" logs job/account-update-db-schema --tail=50 || true
                               exit 1 ;;
            esac
            sleep 2
        done
        echo "The schema job did not complete within 5 minutes"
        exit 1
    '''
}

// The end-to-end suite against the Services, from inside the cluster. The rate-limit burst is left out: it would
// throttle this pod for the rest of the run.
def endToEndTests(String suite = 'account-upgrade-e2e') {
    dir(suite) {
        sh '''./mvnw -B -ntp verify -DexcludedGroups=rate-limit \
                -De2e.baseUrl="$UI_URL" -De2e.apiUrl="$API_URL" \
                -De2e.kubeContext=in-cluster -De2e.namespace="$NAMESPACE"'''
    }
}

def rollBack() {
    def canRollBack = env.PREVIOUS_BACKEND && env.PREVIOUS_FRONTEND
    if (!canRollBack) {
        currentBuild.description = "FAILED ${env.RELEASE?.take(8)} (nothing to roll back to)"
        showPodsAndLogs()
        return
    }
    showPodsAndLogs()
    echo "Rolling back to ${env.PREVIOUS_BACKEND}, ${env.PREVIOUS_FRONTEND}"
    try {
        // The previous release is its images *and* its manifests: a broken setting in deploy/k8s must go too. The
        // image tag is the commit it was built from; its manifests and end-to-end tests are extracted into rollback/.
        def base = 'deploy/k8s/base'
        def suite = 'account-upgrade-e2e'
        def previousCommit = env.PREVIOUS_BACKEND.tokenize(':').last()
        if (extractRelease(previousCommit)) {
            base = 'rollback/deploy/k8s/base'
            if (fileExists('rollback/account-upgrade-e2e/pom.xml')) {
                suite = 'rollback/account-upgrade-e2e'
            }
        }
        else {
            echo "WARNING: ${previousCommit} is not a commit of this repository; rolling back the images only, " +
                 "with this commit's manifests"
        }
        // A cluster deployed before the schema Job existed has no previous schema image; the new one is a no-op then.
        release([env.PREVIOUS_SCHEMA ?: "${env.REGISTRY}/account-update-db-schema:${env.RELEASE}".toString(),
                 env.PREVIOUS_BACKEND, env.PREVIOUS_FRONTEND], base)
        endToEndTests(suite)
        currentBuild.description = "ROLLED BACK ${env.RELEASE.take(8)}, restored ${env.PREVIOUS_BACKEND.tokenize(':').last().take(8)}"
    }
    catch (e) {
        currentBuild.description = "FAILED ${env.RELEASE.take(8)}, rollback failed too"
        echo "Rollback failed: ${e}"
    }
}

// Extracts deploy/k8s (and account-upgrade-e2e, if it has one) of the given commit into rollback/. False if the
// value is not a commit of this repository.
def extractRelease(String commit) {
    if (!(commit ==~ /[0-9a-f]{40}/)) {
        return false
    }
    return sh(returnStatus: true, script: """
        git cat-file -e ${commit}^{commit} 2>/dev/null || git fetch --quiet origin ${commit} || exit 1
        git cat-file -e ${commit}:deploy/k8s/base 2>/dev/null || exit 1
        rm -rf rollback && mkdir rollback
        git archive ${commit} deploy/k8s | tar -x -C rollback
        if git cat-file -e ${commit}:account-upgrade-e2e 2>/dev/null; then
            git archive ${commit} account-upgrade-e2e | tar -x -C rollback
            chmod +x rollback/account-upgrade-e2e/mvnw
        fi
    """) == 0
}

def showPodsAndLogs() {
    sh '''
        kubectl -n "$NAMESPACE" get pods -o wide || true
        for component in db-schema backend frontend; do
            kubectl -n "$NAMESPACE" logs --selector "app.kubernetes.io/component=$component" --all-containers \
                --prefix --tail 100 --max-log-requests 10 || true
        done
    '''
}

// Keeps the images in use, the previous release's and the KEEP_IMAGES newest others of each service.
def removeOldImages(List<String> services) {
    def keep = [env.PREVIOUS_SCHEMA, env.PREVIOUS_BACKEND, env.PREVIOUS_FRONTEND] +
               services.collect { "${env.REGISTRY}/${it}:${env.RELEASE}".toString() }
    for (service in services) {
        def images = sh(script: "docker images ${env.REGISTRY}/${service} --format '{{.Repository}}:{{.Tag}}'",
                        returnStdout: true).readLines().findAll { it && !it.endsWith(':<none>') && !keep.contains(it) }
        def remove = images.drop(env.KEEP_IMAGES as int)
        if (remove) {
            sh "docker rmi ${remove.join(' ')} || true"
        }
    }
}
