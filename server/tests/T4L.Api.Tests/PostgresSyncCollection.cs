using Xunit;

namespace T4L.Api.Tests;

[CollectionDefinition(Name, DisableParallelization = true)]
public sealed class PostgresSyncTestGroup
{
    public const string Name = "PostgreSQL sync integration";
}
