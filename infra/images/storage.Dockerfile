FROM rustfs/rustfs:1.0.0-beta.12@sha256:41fe89380f4120a337790c02af192c3fe7bb55c3edc2e6e9357b487b47c6ab21
USER root
RUN apk add --no-cache 'libssl3>=3.5.8-r0' 'libcrypto3>=3.5.8-r0'
USER 10001:10001
