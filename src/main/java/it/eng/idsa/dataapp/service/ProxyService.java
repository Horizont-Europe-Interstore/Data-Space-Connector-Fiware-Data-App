package it.eng.idsa.dataapp.service;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import it.eng.idsa.dataapp.domain.ProxyRequest;
import it.eng.idsa.dataapp.model.RequestContract;

public interface ProxyService {
	ResponseEntity<String> proxyMultipartMix(ProxyRequest proxyRequest, HttpHeaders httpHeaders)
			throws URISyntaxException;

	ResponseEntity<String> proxyMultipartForm(ProxyRequest proxyRequest, HttpHeaders httpHeaders)
			throws URISyntaxException;

	ResponseEntity<String> proxyHttpHeader(ProxyRequest proxyRequest, HttpHeaders httpHeaders)
			throws URISyntaxException;

	ResponseEntity<String> proxyRegistrationEntityForm(String payloadOrion, String entityId, String forwardTo,
			URI uri, OrionContextBrokerService orionService, RequestContract requestContract, String smartContractId)
			throws URISyntaxException;

	ResponseEntity<String> requestArtifact(ProxyRequest proxyRequest);

	ProxyRequest parseIncomingProxyRequest(String body);

	ResponseEntity<String> proxyWSSRequest(ProxyRequest proxyRequest);

	ResponseEntity<?> proxyCreationEntityForm(HttpHeaders orionHeaders, String body, URI myUri)
			throws URISyntaxException;
}
