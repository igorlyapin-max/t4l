using System.Text.Json;
using System.Text.RegularExpressions;
using T4L.Api.Domain;

namespace T4L.Api.Sync;

internal sealed record PaletteContents(HashSet<Guid> CategoryIds, HashSet<Guid> TaskIds);

internal static class PaletteValidation
{
    internal const int MaxCategories = 1_000;
    internal const int MaxOrderItems = 2_000;
    private static readonly Regex ColorPattern = new("^#[0-9A-Fa-f]{6}$", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    internal static PaletteContents Parse(PaletteEntity palette)
    {
        if (string.IsNullOrWhiteSpace(palette.Name) || palette.Name.Length > 120 ||
            palette.CategoryColorsJson is null or { Length: > 100_000 } ||
            palette.ItemOrderJson is null or { Length: > 100_000 })
            throw new ArgumentException("invalid_palette");

        try
        {
            using var colors = JsonDocument.Parse(palette.CategoryColorsJson);
            using var order = JsonDocument.Parse(palette.ItemOrderJson);
            if (colors.RootElement.ValueKind != JsonValueKind.Object || order.RootElement.ValueKind != JsonValueKind.Array)
                throw new ArgumentException("invalid_palette");

            var categoryIds = new HashSet<Guid>();
            foreach (var color in colors.RootElement.EnumerateObject())
            {
                if (categoryIds.Count >= MaxCategories || !Guid.TryParse(color.Name, out var id) || !categoryIds.Add(id) ||
                    color.Value.ValueKind is not (JsonValueKind.Null or JsonValueKind.String) ||
                    color.Value.ValueKind == JsonValueKind.String && !ColorPattern.IsMatch(color.Value.GetString()!))
                    throw new ArgumentException("invalid_palette_category");
            }

            var orderedCategories = new HashSet<Guid>();
            var taskIds = new HashSet<Guid>();
            var orderKeys = new HashSet<(char Kind, Guid Id)>();
            foreach (var value in order.RootElement.EnumerateArray())
            {
                if (orderKeys.Count >= MaxOrderItems || value.ValueKind != JsonValueKind.String)
                    throw new ArgumentException("invalid_palette_order");
                var key = value.GetString()!;
                if (key.Length < 3 || key[1] != ':' || key[0] is not ('c' or 't') ||
                    !Guid.TryParse(key[2..], out var id) || !orderKeys.Add((key[0], id)))
                    throw new ArgumentException("invalid_palette_order");
                if (key[0] == 'c') orderedCategories.Add(id);
                else taskIds.Add(id);
            }
            if (!orderedCategories.SetEquals(categoryIds)) throw new ArgumentException("invalid_palette_order");
            return new PaletteContents(categoryIds, taskIds);
        }
        catch (JsonException exception)
        {
            throw new ArgumentException("invalid_palette", exception);
        }
    }
}
