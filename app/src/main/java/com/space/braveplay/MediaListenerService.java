package com.space.braveplay;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener.
 *
 * <p>Its only purpose is the permission: being an enabled notification listener is what
 * allows {@code MediaSessionManager.getActiveSessions()} to be used, which the music
 * keeper needs to forward play/pause from the car's media buttons to Brave.</p>
 *
 * <p>No notification content is read or stored anywhere.</p>
 */
public class MediaListenerService extends NotificationListenerService {
}
