# syntax=docker/dockerfile:1
# Jenkins for the Account Upgrade CI/CD pipeline (Jenkinsfile at the repository root): the controller, its plugins and
# its configuration (casc.yaml, the pipeline job included), plus the tools the pipeline runs: a JDK (the image's), git,
# the Docker CLI with buildx (image builds, Trivy, the backend's Testcontainers) and kubectl (deployments).
#   docker build -t local/account-upgrade-jenkins:2.568.3 jenkins
# Deployed into the local Kubernetes cluster by jenkins/k8s; see jenkins/README.md.

FROM docker:29.8.1-cli AS docker-cli

FROM jenkins/jenkins:2.568.3-lts-jdk21

USER root

# Docker CLI with the buildx plugin: the service images need BuildKit (COPY --chmod, ADD --checksum).
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx

# kubectl, the cluster's minor version, verified against its published checksum.
ARG KUBECTL_VERSION=v1.36.4
RUN set -eu; \
    cd /tmp; \
    curl -fsSLO "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl"; \
    curl -fsSLO "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl.sha256"; \
    echo "$(cat kubectl.sha256)  kubectl" | sha256sum -c -; \
    install -m 0755 kubectl /usr/local/bin/kubectl; \
    rm kubectl kubectl.sha256

USER jenkins

# Plugins resolved at image build time; rebuild the image to update them (see README.md).
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Configuration as code: security, the pipeline job, no setup wizard.
COPY casc.yaml /usr/share/jenkins/ref/casc.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc.yaml \
    JAVA_OPTS="-Djenkins.install.runSetupWizard=false -XX:MaxRAMPercentage=25"
