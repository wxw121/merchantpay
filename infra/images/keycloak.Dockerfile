FROM quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244 AS distribution
FROM container-registry.oracle.com/java/jdk-no-fee-term:25.0.5@sha256:283d01db5d749b005826ad311af50a78cc40969548abb63d4b57063a6d611960
COPY --from=distribution --chown=1000:0 /opt/keycloak /opt/keycloak
ENV KC_RUN_IN_CONTAINER=true HOME=/opt/keycloak LANG=en_US.UTF-8
WORKDIR /opt/keycloak
USER 1000:0
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
