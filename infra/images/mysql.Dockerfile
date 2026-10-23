# Use the official signed October maintenance RPM; fail if the repository moves past this reviewed release.
FROM mysql:8.4@sha256:0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364
RUN microdnf remove -y mysql-shell && microdnf update -y "mysql-community-*" && mysql --version | grep -F "8.4.13" && microdnf clean all && rm -f /usr/local/bin/gosu
ENV MYSQL_VERSION=8.4.13-1.el9
# The official entrypoint initializes an owned volume without its root/gosu branch.
USER mysql
