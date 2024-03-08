package it.eng.idsa.dataapp.model;

import java.io.Serializable;

/**
 * Class to wrap up original request, so it can be sent via Connectors, and
 * recreated at other side
 * 
 * @author igor.balog
 *
 */
public class OrionRequest implements Serializable {
	private String entityId;
	private String origin;

	public OrionRequest() {
		super();
	}

	public OrionRequest(String entityId, String origin) {
		super();
		this.entityId = entityId;
		this.origin = origin;
	}

	public String getEntityId() {
		return entityId;
	}

	public void setEntityId(String requestPath) {
		this.entityId = requestPath;
	}

	public String getOrigin() {
		return origin;
	}

	public void setOrigin(String origin) {
		this.origin = origin;
	}
}
