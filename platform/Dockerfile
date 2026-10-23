# Build the jar with Java 25 / Maven before building this runtime image.
FROM container-registry.oracle.com/java/jdk-no-fee-term:25.0.5@sha256:283d01db5d749b005826ad311af50a78cc40969548abb63d4b57063a6d611960
WORKDIR /app
COPY --chown=10001:10001 target/platform-1.0.0-SNAPSHOT.jar /app/app.jar
RUN rm -f /usr/bin/pebble && mkdir -p /data/platform && chown -R 10001:10001 /data/platform
USER 10001:10001
ENV BIND_ADDRESS=0.0.0.0 DATA_DIRECTORY=/data/platform
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
