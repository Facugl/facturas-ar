package ar.facturas.upload.application.port;

import java.util.UUID;

/**
 * Who is making the request.
 *
 * <p>It exists so the controller never has to care. Today the only
 * implementation returns a fixed development user; when authentication
 * lands it will read the security context instead, and nothing above this
 * interface changes.
 *
 * <p>That is the whole point of writing it now rather than passing a
 * userId around: a userId threaded through signatures has to be unpicked
 * later, and a header read in the controller spreads a temporary decision
 * across every endpoint.
 */
public interface CurrentUser {

    UUID id();
}
