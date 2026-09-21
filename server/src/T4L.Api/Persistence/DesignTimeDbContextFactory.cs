using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace T4L.Api.Persistence;

public sealed class DesignTimeDbContextFactory : IDesignTimeDbContextFactory<T4LDbContext>
{
    public T4LDbContext CreateDbContext(string[] args)
    {
        var options = new DbContextOptionsBuilder<T4LDbContext>()
            .UseNpgsql("Host=127.0.0.1;Database=t4l_design;Username=t4l;Password=unused")
            .Options;
        return new T4LDbContext(options);
    }
}
