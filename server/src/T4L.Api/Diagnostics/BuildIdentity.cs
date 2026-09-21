using System.Security.Cryptography;

namespace T4L.Api.Diagnostics;

public sealed record BuildIdentity(
    string Version,
    string GitRevision,
    bool SourceClean,
    string Provenance,
    string RuntimeArtifactSha256)
{
    public static BuildIdentity Load()
    {
        var versionPath = Path.Combine(AppContext.BaseDirectory, "VERSION");
        if (!File.Exists(versionPath)) throw new InvalidOperationException("Embedded VERSION is missing.");
        var version = File.ReadAllText(versionPath).Trim();
        if (!System.Text.RegularExpressions.Regex.IsMatch(version, "^[0-9]{2}(\\.[0-9]{2}){3}$"))
            throw new InvalidOperationException("Embedded VERSION is invalid.");

        var provenance = Environment.GetEnvironmentVariable("T4L_BUILD_PROVENANCE") ?? "unverified-local";
        var configuredVersion = Environment.GetEnvironmentVariable("T4L_BUILD_VERSION");
        var revision = Environment.GetEnvironmentVariable("T4L_GIT_REVISION") ?? "unknown";
        var sourceClean = bool.TryParse(Environment.GetEnvironmentVariable("T4L_SOURCE_CLEAN"), out var clean) && clean;
        var configuredDigest = Environment.GetEnvironmentVariable("T4L_RUNTIME_ARTIFACT_SHA256") ?? "unknown";
        var artifactPath = Path.Combine(AppContext.BaseDirectory, "T4L.Api.dll");
        var actualDigest = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(artifactPath))).ToLowerInvariant();

        if (provenance.Equals("verified", StringComparison.OrdinalIgnoreCase))
        {
            if (configuredVersion != version || revision.Length != 40 || revision.Any(c => !Uri.IsHexDigit(c)) ||
                !sourceClean || !configuredDigest.Equals(actualDigest, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Verified build identity is incomplete or inconsistent.");
        }

        return new BuildIdentity(version, revision, sourceClean, provenance, actualDigest);
    }
}
