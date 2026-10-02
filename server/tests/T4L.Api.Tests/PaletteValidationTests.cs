using System.Text.Json;
using T4L.Api.Domain;
using T4L.Api.Sync;
using Xunit;

namespace T4L.Api.Tests;

public sealed class PaletteValidationTests
{
    [Fact]
    public void ValidPalettePreservesCategoryAndTaskReferences()
    {
        var category = Guid.NewGuid(); var task = Guid.NewGuid();
        var parsed = PaletteValidation.Parse(new PaletteEntity {
            Name = "Daily", CategoryColorsJson = JsonSerializer.Serialize(new Dictionary<Guid, string?> { [category] = "#A0b1C2" }),
            ItemOrderJson = JsonSerializer.Serialize(new[] { $"t:{task:D}", $"c:{category:D}" })
        });
        Assert.Contains(category, parsed.CategoryIds);
        Assert.Contains(task, parsed.TaskIds);
    }

    [Theory]
    [InlineData(null, "[]")]
    [InlineData("{", "[]")]
    [InlineData("[]", "[]")]
    [InlineData("{\"not-a-uuid\":null}", "[]")]
    [InlineData("{\"00000000-0000-0000-0000-000000000001\":123}", "[\"c:00000000-0000-0000-0000-000000000001\"]")]
    [InlineData("{\"00000000-0000-0000-0000-000000000001\":\"red\"}", "[\"c:00000000-0000-0000-0000-000000000001\"]")]
    [InlineData("{}", "[\"t:bad\"]")]
    [InlineData("{}", "[1]")]
    [InlineData("{}", "[\"t:00000000-0000-0000-0000-000000000001\",\"t:00000000-0000-0000-0000-000000000001\"]")]
    public void MalformedPaletteIsRejected(string? colors, string order)
    {
        Assert.Throws<ArgumentException>(() => PaletteValidation.Parse(new PaletteEntity {
            Name = "Daily", CategoryColorsJson = colors!, ItemOrderJson = order
        }));
    }

    [Fact]
    public void PaletteLimitsAreEnforced()
    {
        var categoryIds = Enumerable.Range(1, PaletteValidation.MaxCategories + 1)
            .Select(value => new Guid(value, 0, 0, new byte[8])).ToArray();
        var tooManyCategories = new PaletteEntity { Name = "Daily",
            CategoryColorsJson = JsonSerializer.Serialize(categoryIds.ToDictionary(x => x.ToString("D"), _ => (string?)null)),
            ItemOrderJson = "[]" };
        Assert.Throws<ArgumentException>(() => PaletteValidation.Parse(tooManyCategories));
        Assert.Throws<ArgumentException>(() => PaletteValidation.Parse(new PaletteEntity { Name = new string('x', 121) }));
    }
}
