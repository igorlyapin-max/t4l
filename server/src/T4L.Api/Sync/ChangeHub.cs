using Microsoft.AspNetCore.SignalR;
using T4L.Api.Domain;
using T4L.Api.Security;

namespace T4L.Api.Sync;

public sealed class ChangeHub(WorkspaceAccess workspaceAccess) : Hub
{
    public async Task WatchWorkspace(Guid workspaceId)
    {
        await workspaceAccess.RequireAsync(workspaceId, MembershipRole.Viewer, Context.ConnectionAborted);
        await Groups.AddToGroupAsync(Context.ConnectionId, workspaceId.ToString("D"), Context.ConnectionAborted);
    }
}
