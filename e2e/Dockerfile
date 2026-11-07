# docker CLI: scenarios stop and start fab services through the mounted Docker socket
FROM docker:27.5.1-cli
RUN apk add --no-cache bash curl jq gawk mariadb-client redis
COPY run.sh /e2e/run.sh
ENTRYPOINT ["bash", "/e2e/run.sh"]
