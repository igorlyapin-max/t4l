FROM mcr.microsoft.com/dotnet/sdk:10.0-noble AS build
WORKDIR /src
COPY ["Directory.Build.props", "."]
COPY ["VERSION", "."]
COPY ["server/src/T4L.Api/T4L.Api.csproj", "server/src/T4L.Api/"]
RUN dotnet restore "server/src/T4L.Api/T4L.Api.csproj"
COPY [".editorconfig", "."]
COPY server/src/T4L.Api server/src/T4L.Api
RUN dotnet publish "server/src/T4L.Api/T4L.Api.csproj" -c Release -o /app /p:UseAppHost=false

FROM scratch AS artifact-export
COPY --from=build /app/T4L.Api.dll /T4L.Api.dll
COPY --from=build /app/VERSION /VERSION

FROM mcr.microsoft.com/dotnet/aspnet:10.0-noble AS runtime
RUN apt-get update \
 && apt-get install --yes --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
RUN groupadd --system appgroup \
 && useradd --system --gid appgroup --no-create-home --shell /usr/sbin/nologin appuser
ARG T4L_BUILD_VERSION=00.00.00.00
ARG T4L_GIT_REVISION=unknown
ARG T4L_SOURCE_CLEAN=false
ARG T4L_BUILD_PROVENANCE=unverified-local
ARG T4L_RUNTIME_ARTIFACT_SHA256=unknown
LABEL org.opencontainers.image.version="${T4L_BUILD_VERSION}" \
      org.opencontainers.image.revision="${T4L_GIT_REVISION}" \
      app.t4l.source-clean="${T4L_SOURCE_CLEAN}" \
      app.t4l.provenance="${T4L_BUILD_PROVENANCE}" \
      app.t4l.runtime-artifact-sha256="${T4L_RUNTIME_ARTIFACT_SHA256}"
ENV T4L_BUILD_VERSION="${T4L_BUILD_VERSION}" \
    T4L_GIT_REVISION="${T4L_GIT_REVISION}" \
    T4L_SOURCE_CLEAN="${T4L_SOURCE_CLEAN}" \
    T4L_BUILD_PROVENANCE="${T4L_BUILD_PROVENANCE}" \
    T4L_RUNTIME_ARTIFACT_SHA256="${T4L_RUNTIME_ARTIFACT_SHA256}"
WORKDIR /app
COPY --from=build --chown=appuser:appgroup /app .
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=6 CMD ["curl", "--fail", "--silent", "http://127.0.0.1:8080/health/ready"]
ENTRYPOINT ["dotnet", "T4L.Api.dll"]
