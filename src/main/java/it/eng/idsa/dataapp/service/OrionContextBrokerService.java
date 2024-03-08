package it.eng.idsa.dataapp.service;

import java.net.URISyntaxException;

import org.bson.Document;

import it.eng.idsa.dataapp.model.OrionRequest;
import it.eng.idsa.dataapp.model.RequestContract;

public interface OrionContextBrokerService {

	String GetEntity(OrionRequest orionRequest) throws URISyntaxException;

	Document GetReference(String entityId);

	void StoreEntity(String entityId, String forwardTo, RequestContract requestContract, String smartContractId);

}
