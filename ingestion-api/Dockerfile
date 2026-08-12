FROM eclipse-temurin:17-jre-alpine

RUN addgroup -g 1000 app && adduser -D -u 1000 -G app app

WORKDIR /app
COPY --chown=1000:1000 build/install/ingestion-api/ ./

USER 1000
EXPOSE 8039
ENTRYPOINT ["/app/bin/ingestion-api"]
