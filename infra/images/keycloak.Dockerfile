FROM quay.io/keycloak/keycloak:26.8.2@sha256:c68e286cacd87b942ac1939a0a1481461109d6acd3a57dc496969a319a76bbf0 AS distribution
FROM container-registry.oracle.com/java/jdk-no-fee-term:25.0.5@sha256:283d01db5d749b005826ad311af50a78cc40969548abb63d4b57063a6d611960
COPY --from=distribution --chown=1000:0 /opt/keycloak /opt/keycloak
ADD --checksum=sha256:b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar /tmp/guava-33.7.2-jre.jar
RUN test -f /opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar && mv /tmp/guava-33.7.2-jre.jar /opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar && dnf -y update && dnf clean all && /opt/keycloak/bin/kc.sh --version | grep -F '26.8.2'
ENV KC_RUN_IN_CONTAINER=true HOME=/opt/keycloak LANG=en_US.UTF-8
WORKDIR /opt/keycloak
USER 1000:0
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
