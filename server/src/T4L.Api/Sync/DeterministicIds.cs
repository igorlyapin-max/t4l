using System.Security.Cryptography;
using System.Text;

namespace T4L.Api.Sync;

public static class DeterministicIds
{
    public static Guid Named(string name)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(name))[..16];
        bytes[6] = (byte)((bytes[6] & 0x0f) | 0x50);
        bytes[8] = (byte)((bytes[8] & 0x3f) | 0x80);
        return new Guid(bytes, bigEndian: true);
    }

    public static Guid BudgetAllocation(Guid workspaceId, Guid planId, Guid categoryId)
        => Named($"budget-allocation:{workspaceId:D}:{planId:D}:{categoryId:D}".ToLowerInvariant());
}
