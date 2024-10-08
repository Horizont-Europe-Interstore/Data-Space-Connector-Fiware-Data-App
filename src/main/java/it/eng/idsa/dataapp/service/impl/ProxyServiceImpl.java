package it.eng.idsa.dataapp.service.impl;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.util.Base64;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.protocol.HTTP;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import de.fraunhofer.iais.eis.ArtifactRequestMessage;
import de.fraunhofer.iais.eis.ArtifactRequestMessageBuilder;
import de.fraunhofer.iais.eis.ArtifactResponseMessage;
import de.fraunhofer.iais.eis.ConnectorUnavailableMessage;
import de.fraunhofer.iais.eis.ConnectorUpdateMessage;
import de.fraunhofer.iais.eis.ContractAgreementMessage;
import de.fraunhofer.iais.eis.ContractAgreementMessageBuilder;
import de.fraunhofer.iais.eis.ContractRequestMessage;
import de.fraunhofer.iais.eis.ContractRequestMessageBuilder;
import de.fraunhofer.iais.eis.DescriptionRequestMessage;
import de.fraunhofer.iais.eis.DescriptionRequestMessageBuilder;
import de.fraunhofer.iais.eis.Message;
import de.fraunhofer.iais.eis.MessageProcessedNotificationMessage;
import de.fraunhofer.iais.eis.QueryMessage;
import de.fraunhofer.iais.eis.RejectionMessage;
import de.fraunhofer.iais.eis.RejectionReason;
import de.fraunhofer.iais.eis.TokenFormat;
import de.fraunhofer.iais.eis.ids.jsonld.Serializer;
import de.fraunhofer.iais.eis.util.Util;
import it.eng.idsa.dataapp.configuration.ECCProperties;
import it.eng.idsa.dataapp.domain.ProxyRequest;
import it.eng.idsa.dataapp.model.RequestContract;
import it.eng.idsa.dataapp.service.OrionContextBrokerService;
import it.eng.idsa.dataapp.service.ProxyService;
import it.eng.idsa.dataapp.service.RecreateFileService;
import it.eng.idsa.dataapp.util.ConstantUtil;
import it.eng.idsa.dataapp.util.MessageUtil;
import it.eng.idsa.dataapp.util.RejectionUtil;
import it.eng.idsa.dataapp.web.rest.exceptions.ConflictOrionException;
import it.eng.idsa.multipart.builder.MultipartMessageBuilder;
import it.eng.idsa.multipart.domain.MultipartMessage;
import it.eng.idsa.multipart.processor.MultipartMessageProcessor;
import it.eng.idsa.multipart.util.DateUtil;
import it.eng.idsa.multipart.util.UtilMessageService;
import it.eng.idsa.streamer.WebSocketClientManager;
import it.eng.idsa.streamer.websocket.receiver.server.FileRecreatorBeanExecutor;

@Service
public class ProxyServiceImpl implements ProxyService {

	@Value("${application.mongo.host}")
	private String host;

	@Value("${application.mongo.port}")
	private String port;

	private static final String MULTIPART = "multipart";
	private static final String MESSAGE_TYPE = "messageType";
	private static final String PAYLOAD = "payload";
	private static final String REQUESTED_ARTIFACT = "requestedArtifact";
	private static final String FORWARD_TO_INTERNAL = "Forward-To-Internal";
	private static final String REQUESTED_ELEMENT = "requestedElement";
	private static final String TRANSFER_CONTRACT = "transferContract";
	private static final String FORWARD_TO = "Forward-To";

	private static final Logger logger = LoggerFactory.getLogger(ProxyService.class);

	private RestTemplate restTemplate;
	private ECCProperties eccProperties;
	private RecreateFileService recreateFileService;
	private String dataLakeDirectory;
	private String issueConnector;
	private Boolean encodePayload;
	private Boolean extractPayloadFromResponse;
	private MessageUtil messageUtil;

	public ProxyServiceImpl(RestTemplateBuilder restTemplateBuilder, ECCProperties eccProperties,
			RecreateFileService recreateFileService,
			@Value("${application.dataLakeDirectory}") String dataLakeDirectory,
			@Value("${application.ecc.issuer.connector}") String issuerConnector,
			@Value("#{new Boolean('${application.encodePayload:false}')}") Boolean encodePayload,
			@Value("#{new Boolean('${application.extractPayloadFromResponse:false}')}") Boolean extractPayloadFromResponse,
			MessageUtil messageUtil) {
		this.restTemplate = restTemplateBuilder.build();
		this.eccProperties = eccProperties;
		this.recreateFileService = recreateFileService;
		this.dataLakeDirectory = dataLakeDirectory;
		this.issueConnector = issuerConnector;
		this.encodePayload = encodePayload;
		this.extractPayloadFromResponse = extractPayloadFromResponse;
		this.messageUtil = messageUtil;

	}

	@Override
	public ProxyRequest parseIncomingProxyRequest(String body) {
		JSONParser parser = new JSONParser();
		JSONObject jsonObject;
		try {
			jsonObject = (JSONObject) parser.parse(body);

			String multipart = (String) jsonObject.get(MULTIPART);
			String forwardTo = (String) jsonObject.get(FORWARD_TO);
			String forwardToInternal = (String) jsonObject.get(FORWARD_TO_INTERNAL);
			String requestedArtifact = (String) jsonObject.get(REQUESTED_ARTIFACT);
			String messageType = (String) jsonObject.get(MESSAGE_TYPE);
			String requestedElement = (String) jsonObject.get(REQUESTED_ELEMENT);
			String transferContract = (String) jsonObject.get(TRANSFER_CONTRACT);

			String payload = null;
			if (jsonObject.get(PAYLOAD) instanceof String) {
				payload = ((String) jsonObject.get(PAYLOAD)).replace("\\/", "/").replace("\\", "");
			} else {
				JSONObject partJson = (JSONObject) jsonObject.get(PAYLOAD);
				payload = partJson != null ? partJson.toJSONString().replace("\\/", "/") : null;
			}

			return new ProxyRequest(multipart, forwardTo, forwardToInternal, payload, requestedArtifact, messageType,
					requestedElement, transferContract);
		} catch (ParseException e) {
			logger.error("Error parsing payoad", e);
		}
		return new ProxyRequest();
	}

	@Override
	public ResponseEntity<String> proxyMultipartMix(ProxyRequest proxyRequest, HttpHeaders httpHeaders)
			throws URISyntaxException {

		URI thirdPartyApi = null;
		String proxyPayload = null;
		httpHeaders.add(ConstantUtil.FORWARD_TO, proxyRequest.getForwardTo());

		Message requestMessage = createRequestMessage(proxyRequest.getMessageType(),
				proxyRequest.getRequestedArtifact(), proxyRequest.getRequestedElement());

		if (requestMessage != null) {
			String payload = null;
			if (requestMessage instanceof ContractRequestMessage && proxyRequest.getPayload() == null) {
				logger.info("Creating ContractRequest for payload using requested artifact");
				payload = UtilMessageService.getMessageAsString(
						UtilMessageService.getContractRequest(URI.create(proxyRequest.getRequestedElement()),
								URI.create("http://permission.id")));
			} else {
				logger.info("Using payload from request");
				payload = proxyRequest.getPayload();
			}

			MultipartMessage mm = new MultipartMessageBuilder()
					.withHeaderContent(requestMessage)
					.withPayloadContent(payload)
					.build();
			proxyPayload = MultipartMessageProcessor.multipartMessagetoString(mm, false, true);

			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getMixContext(),
					null, null);

		} else if (ConnectorUpdateMessage.class.getSimpleName().equals(proxyRequest.getMessageType())) {
			logger.info("Broker message - ConnectorUpdateMessage");
			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getBrokerRegisterContext(),
					null, null);
		} else if (ConnectorUnavailableMessage.class.getSimpleName().equals(proxyRequest.getMessageType())) {
			logger.info("Broker message - ConnectorUnavailableMessage");
			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getBrokerDeleteContext(),
					null, null);
		} else if (QueryMessage.class.getSimpleName().equals(proxyRequest.getMessageType())) {
			logger.info("Broker message - QueryMessage");
			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getBrokerQuerryContext(),
					null, null);
			proxyPayload = proxyRequest.getPayload();
		}

		logger.info("Forwarding mix POST request to {}", thirdPartyApi.toString());

		HttpEntity<String> requestEntity = new HttpEntity<String>(proxyPayload, httpHeaders);
		ResponseEntity<String> resp = restTemplate.exchange(thirdPartyApi, HttpMethod.POST, requestEntity,
				String.class);
		logResponse(resp);
		return resp;
	}

	@Override
	public ResponseEntity<String> proxyMultipartForm(ProxyRequest proxyRequest, HttpHeaders httpHeaders)
			throws URISyntaxException {

		Message requestMessage = createRequestMessage(proxyRequest);
		if (logger.isDebugEnabled()) {
			logger.debug("Created request message {}", UtilMessageService.getMessageAsString(requestMessage));
		}
		URI thirdPartyApi = null;
		HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = null;
		LinkedMultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
		httpHeaders.add(ConstantUtil.FORWARD_TO, proxyRequest.getForwardTo());
		httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

		if (requestMessage != null) {
			map.add("header", UtilMessageService.getMessageAsString(requestMessage));
			String payload = null;
			if (requestMessage instanceof ContractRequestMessage && proxyRequest.getPayload() == null) {
				logger.info("Creating ContractRequest for payload using requested artifact");
				payload = UtilMessageService.getMessageAsString(
						UtilMessageService.getContractRequest(URI.create(proxyRequest.getRequestedElement()),
								URI.create("http://permission.id")));
			} else {
				logger.info("Using payload from request");
				payload = proxyRequest.getPayload();
			}
			logger.info("ENG: proxy-payload = " + payload);

			map.add("payload", payload);
			if (logger.isDebugEnabled()) {
				logger.debug("Created payload {}", payload);
			}
			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getFormContext(),
					null, null);
		} else if (ConnectorUpdateMessage.class.getSimpleName().equals(proxyRequest.getMessageType())) {
			logger.info("Broker message - ConnectorUpdateMessage");
			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getBrokerRegisterContext(),
					null, null);
		} else if (ConnectorUnavailableMessage.class.getSimpleName().equals(proxyRequest.getMessageType())) {
			logger.info("Broker message - ConnectorUnavailableMessage");
			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getBrokerDeleteContext(),
					null, null);
		} else if (QueryMessage.class.getSimpleName().equals(proxyRequest.getMessageType())) {
			logger.info("Broker message - QueryMessage");
			map.add("payload", proxyRequest.getPayload());
			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getBrokerQuerryContext(),
					null, null);
		}

		logger.info("Forwarding form POST request to {}", thirdPartyApi.toString());
		requestEntity = new HttpEntity<>(map, httpHeaders);
		return sendMultipartRequest(thirdPartyApi, requestEntity);
	}

	private ResponseEntity<String> sendMultipartRequest(URI thirdPartyApi, HttpEntity<?> requestEntity) {
		try {
			ResponseEntity<String> resp = restTemplate.exchange(thirdPartyApi, HttpMethod.POST, requestEntity,
					String.class);
			MultipartMessage mm = MultipartMessageProcessor.parseMultipartMessage(resp.getBody());
			logMultipartResponse(resp, mm);
			return handleResponse(resp, mm);
		} catch (RestClientException e) {
			logger.error("Following error occured: {}", e);
			return new ResponseEntity<String>("Message could not be processed", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private void logMultipartResponse(ResponseEntity<String> resp, MultipartMessage mm) {
		logger.info("Response received with status code {}", resp.getStatusCode());
		logger.info("Response headers\n{}", resp.getHeaders());
		logger.info("Response header part\n{}", mm.getHeaderContentString());
		if (encodePayload && mm.getHeaderContent() instanceof ArtifactResponseMessage) {
			logger.info("Response payload decoded \n{}",
					new String(Base64.getDecoder().decode(mm.getPayloadContent())));
		} else {
			logger.info("Response payload\n{}", mm.getPayloadContent());
		}
	}

	private ResponseEntity<String> handleResponse(ResponseEntity<String> resp, MultipartMessage mm) {
		if (mm.getHeaderContent() instanceof RejectionMessage) {
			if (((RejectionMessage) mm.getHeaderContent()).getRejectionReason() == null) {
				return new ResponseEntity<String>("Error while processing message", HttpStatus.BAD_REQUEST);
			}
			return RejectionUtil.HANDLE_REJECTION(((RejectionMessage) mm.getHeaderContent()).getRejectionReason());
		}

		if (extractPayloadFromResponse) {
			MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();

			headers.putAll(resp.getHeaders());
			// replacing Content Type and Length headers from original message with the ones
			// from payload part
			headers.set(HTTP.CONTENT_TYPE, mm.getPayloadHeader().get(HTTP.CONTENT_TYPE));

			if (mm.getPayloadContent() != null && messageUtil.isValidJSON(mm.getPayloadContent())) {
				headers.set(HTTP.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
			}
			// headers.set(HTTP.CONTENT_LEN, mm.getPayloadHeader().get(HTTP.CONTENT_LEN));
			headers.remove(HTTP.TRANSFER_ENCODING);

			if (mm.getHeaderContent() instanceof MessageProcessedNotificationMessage) {
				return new ResponseEntity<String>("MessageProcessedNotificationMessage", headers, HttpStatus.OK);
			}
			logger.info("headers in payload: {}", headers);
			// return new ResponseEntity<String>("{\"id\": 3}", headers, HttpStatus.OK);
			return new ResponseEntity<String>(mm.getPayloadContent(), headers,
					HttpStatus.OK);
		}
		return resp;
	}

	@Override
	public ResponseEntity<String> proxyRegistrationEntityForm(String payloadOrion, String entityId, String forwardTo,
			URI uri, OrionContextBrokerService orionService,
			RequestContract requestContract, String smartContractId) throws URISyntaxException {

		logger.info("Forwarding form POST request to {}", uri.toString());

		var headers = new HttpHeaders();
		headers.add(HTTP.CONTENT_TYPE, "application/ld+json");
		var requestEntity = new HttpEntity<>(payloadOrion, headers);
		logger.info("ENG: requestEntity              {}", requestEntity);

		logger.info("ENG: calling restTemplate.exchange()... {}", uri);
		ResponseEntity<String> resp = restTemplate.exchange(uri, HttpMethod.POST, requestEntity,
				String.class);

		logger.info("ENG: calling logResponse()...");
		logResponse(resp);

		if (resp.getStatusCodeValue() == 201) {
			orionService.StoreEntity(entityId, forwardTo, requestContract, smartContractId);
		}

		logger.info("ENG: resp.getStatusCode()      {}", resp.getStatusCode());
		logger.info("ENG: resp.getStatusCodeValue() {}", resp.getStatusCodeValue());

		logResponse(resp);

		return resp;
	}

	@Override
	public ResponseEntity<String> proxyHttpHeader(ProxyRequest proxyRequest, HttpHeaders httpHeaders)
			throws URISyntaxException {
		URI thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
				eccProperties.getPort(), eccProperties.getHeaderContext(),
				null, null);

		logger.info("Forwarding header POST request to {}", thirdPartyApi.toString());
		httpHeaders.addAll(createMessageAsHeader(proxyRequest));
		httpHeaders.add(ConstantUtil.FORWARD_TO, proxyRequest.getForwardTo());

		String payload = null;
		if (proxyRequest.getMessageType().contains("ContractRequestMessage") && proxyRequest.getPayload() == null) {
			logger.info("Creating ContractRequest for payload using requested artifact");
			payload = UtilMessageService.getMessageAsString(
					UtilMessageService.getContractRequest(URI.create(proxyRequest.getRequestedElement()),
							URI.create("http://permission.id")));
		} else {
			logger.info("Using payload from request");
			payload = proxyRequest.getPayload();
		}
		HttpEntity<String> requestEntity = new HttpEntity<>(payload, httpHeaders);

		if (ConnectorUpdateMessage.class.getSimpleName().equals(proxyRequest.getMessageType())) {
			logger.info("Broker message - ConnectorUpdateMessage");
			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getBrokerRegisterContext(),
					null, null);
		} else if (ConnectorUnavailableMessage.class.getSimpleName().equals(proxyRequest.getMessageType())) {
			logger.info("Broker message - ConnectorUnavailableMessage");
			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getBrokerDeleteContext(),
					null, null);
		} else if (QueryMessage.class.getSimpleName().equals(proxyRequest.getMessageType())) {
			logger.info("Broker message - QueryMessage");
			thirdPartyApi = new URI(eccProperties.getProtocol(), null, eccProperties.getHost(),
					eccProperties.getPort(), eccProperties.getBrokerQuerryContext(),
					null, null);
		}

		return sendHttpHeadersRequest(thirdPartyApi, requestEntity);
	}

	private ResponseEntity<String> sendHttpHeadersRequest(URI thirdPartyApi, HttpEntity<String> requestEntity) {
		try {
			ResponseEntity<String> resp = restTemplate.exchange(thirdPartyApi, HttpMethod.POST, requestEntity,
					String.class);
			logHttpHeadersResponse(resp);
			return handleHttpHeadersResponse(resp);
		} catch (RestClientException e) {
			logger.error("Following error occured: {}", e);
			return new ResponseEntity<String>("Message could not be processed", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private void logHttpHeadersResponse(ResponseEntity<String> resp) {
		logger.info("Response received with status code {}", resp.getStatusCode());
		logger.info("Response headers\n{}", resp.getHeaders());
		if (encodePayload && resp.getHeaders().get("IDS-Messagetype").get(0).equals("ArtifactResponseMessage")) {
			logger.info("Response payload decoded \n{}", new String(Base64.getDecoder().decode(resp.getBody())));
		} else {
			logger.info("Response payload\n{}", resp.getBody());
		}
	}

	private ResponseEntity<String> handleHttpHeadersResponse(ResponseEntity<String> resp) {
		if (resp.getHeaders().size() != 0
				&& StringUtils.isNotBlank(resp.getHeaders().get("IDS-Messagetype").get(0))
				&& "ids:RejectionMessage".equals(resp.getHeaders().get("IDS-Messagetype").get(0))) {
			if (StringUtils.isBlank(resp.getHeaders().get("IDS-RejectionReason").get(0))) {
				return new ResponseEntity<String>("Error while processing message", HttpStatus.BAD_REQUEST);
			}

			return RejectionUtil.HANDLE_REJECTION(RejectionReason.valueOf(resp.getHeaders().get("IDS-RejectionReason")
					.get(0).substring(resp.getHeaders().get("IDS-RejectionReason").get(0).lastIndexOf("/") + 1)));
		}

		if (extractPayloadFromResponse) {
			if (resp.getHeaders().size() != 0
					&& StringUtils.isNotBlank(resp.getHeaders().get("IDS-Messagetype").get(0))
					&& "ids:MessageProcessedNotificationMessage"
							.equals(resp.getHeaders().get("IDS-Messagetype").get(0))) {

				return new ResponseEntity<String>("MessageProcessedNotificationMessage",
						MessageUtil.REMOVE_IDS_MESSAGE_HEADERS(resp.getHeaders()), HttpStatus.OK);
			}
			return new ResponseEntity<String>(resp.getBody(), MessageUtil.REMOVE_IDS_MESSAGE_HEADERS(resp.getHeaders()),
					HttpStatus.OK);
		}

		return resp;
	}

	@Override
	public ResponseEntity<String> requestArtifact(ProxyRequest proxyRequest) {
		String forwardToInternal = proxyRequest.getForwardToInternal();
		String forwardTo = proxyRequest.getForwardTo();
		logger.info("Proxying wss ArtifactRequestMessage...");

		if (StringUtils.isEmpty(forwardTo) || StringUtils.isEmpty(forwardToInternal)) {
			return ResponseEntity.badRequest().body("Missing required fields Forward-To or Forward-To-Internal");
		}
		Message artifactRequestMessage;
		try {
			URI transferContract = null;
			if (proxyRequest.getTransferContract() != null) {
				transferContract = URI.create(proxyRequest.getTransferContract());
			}
			artifactRequestMessage = new ArtifactRequestMessageBuilder()
					._issued_(DateUtil.now())
					._issuerConnector_(URI.create(issueConnector))
					._modelVersion_(UtilMessageService.MODEL_VERSION)
					._requestedArtifact_(URI.create(proxyRequest.getRequestedArtifact()))
					._securityToken_(UtilMessageService.getDynamicAttributeToken())
					._senderAgent_(UtilMessageService.SENDER_AGENT)
					._transferContract_(transferContract)
					.build();

			Serializer serializer = new Serializer();
			String requestMessage = serializer.serialize(artifactRequestMessage);
			logger.debug("Artifact request message {}", requestMessage);
			FileRecreatorBeanExecutor.getInstance().setForwardTo(forwardTo);
			String responseMessage = WebSocketClientManager.getMessageWebSocketSender()
					.sendMultipartMessageWebSocketOverHttps(requestMessage, proxyRequest.getPayload(),
							forwardToInternal);

			String fileNameSaved = saveFileToDisk(responseMessage, artifactRequestMessage);

			if (fileNameSaved != null) {
				return ResponseEntity.ok("{​​\"message\":\"File '" + fileNameSaved + "' created successfully\"}");
			}
			return ResponseEntity.ok(responseMessage);
		} catch (Exception exc) {
			logger.error("Error while processing request {}", exc);
			throw new ResponseStatusException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"Error while processing request, check logs for more details",
					exc);
		}
	}

	private Message createRequestMessage(String messageType, String requestedArtifact, String requestedElement) {
		if (ArtifactRequestMessage.class.getSimpleName().equals(messageType)) {
			return UtilMessageService.getArtifactRequestMessage(requestedArtifact != null
					? URI.create(requestedArtifact)
					: UtilMessageService.REQUESTED_ARTIFACT);
		} else if (ContractAgreementMessage.class.getSimpleName().equals(messageType)) {
			return UtilMessageService.getContractAgreementMessage();
		} else if (ContractRequestMessage.class.getSimpleName().equals(messageType)) {
			return UtilMessageService.getContractRequestMessage();
		} else if (DescriptionRequestMessage.class.getSimpleName().equals(messageType)) {
			URI reqEl = requestedElement == null ? null : URI.create(requestedElement);
			return UtilMessageService.getDescriptionRequestMessage(reqEl);
		}
		return null;
	}

	private Message createRequestMessage(ProxyRequest proxyRequest) {
		String messageType = proxyRequest.getMessageType();
		if (ArtifactRequestMessage.class.getSimpleName().equals(messageType)) {
			if (StringUtils.isNoneBlank(proxyRequest.getTransferContract())) {
				logger.info("Creating ArtifactRequest message transfer contract from request");
				String artifactURI = proxyRequest.getRequestedArtifact() != null
						? proxyRequest.getRequestedArtifact()
						: UtilMessageService.REQUESTED_ARTIFACT.toString();
				return getArtifactRequestMessageWithTransferContract(artifactURI,
						proxyRequest.getTransferContract());
			} else {
				logger.info("Creating ArtifactRequest message with default transfer contract");
				String artifactURI = proxyRequest.getRequestedArtifact() != null
						? proxyRequest.getRequestedArtifact()
						: UtilMessageService.REQUESTED_ARTIFACT.toString();
				return getArtifactRequestMessageWithTransferContract(artifactURI,
						UtilMessageService.TRANSFER_CONTRACT.toString());
			}

		} else if (ContractAgreementMessage.class.getSimpleName().equals(messageType)) {
			return new ContractAgreementMessageBuilder()
					._modelVersion_(UtilMessageService.MODEL_VERSION)
					// ._transferContract_(URI.create("http://transferedContract"))
					._correlationMessage_(URI.create("http://correlationMessage"))
					._issued_(UtilMessageService.ISSUED)
					._issuerConnector_(URI.create(issueConnector))
					._securityToken_(UtilMessageService.getDynamicAttributeToken())
					._senderAgent_(UtilMessageService.SENDER_AGENT)
					.build();
		} else if (ContractRequestMessage.class.getSimpleName().equals(messageType)) {
			return new ContractRequestMessageBuilder()
					._issued_(UtilMessageService.ISSUED)
					._modelVersion_(UtilMessageService.MODEL_VERSION)
					._issuerConnector_(URI.create(issueConnector))
					._recipientConnector_(Util.asList(URI.create(proxyRequest.getForwardTo())))
					._senderAgent_(UtilMessageService.SENDER_AGENT)
					._securityToken_(UtilMessageService.getDynamicAttributeToken())
					.build();
		} else if (DescriptionRequestMessage.class.getSimpleName().equals(messageType)) {
			URI reqEl = proxyRequest.getRequestedElement() == null ? null
					: URI.create(proxyRequest.getRequestedElement());
			return new DescriptionRequestMessageBuilder()
					._issued_(UtilMessageService.ISSUED)
					._issuerConnector_(URI.create(issueConnector))
					._modelVersion_(UtilMessageService.MODEL_VERSION)
					._requestedElement_(reqEl)
					._senderAgent_(UtilMessageService.SENDER_AGENT)
					._securityToken_(UtilMessageService.getDynamicAttributeToken())
					.build();
		}
		return null;
	}

	private Message getArtifactRequestMessageWithTransferContract(String requestedArtifact, String transferContract) {
		return new ArtifactRequestMessageBuilder()
				._issued_(UtilMessageService.ISSUED)
				._transferContract_(URI.create(transferContract))
				._issuerConnector_(URI.create(issueConnector))
				._modelVersion_(UtilMessageService.MODEL_VERSION)
				._requestedArtifact_(URI.create(requestedArtifact))
				._senderAgent_(UtilMessageService.SENDER_AGENT)
				._securityToken_(UtilMessageService.getDynamicAttributeToken())
				.build();
	}

	private HttpHeaders createMessageAsHeader(ProxyRequest proxyRequest) {
		String messageType = proxyRequest.getMessageType();
		HttpHeaders httpHeaders = new HttpHeaders();
		if (ArtifactRequestMessage.class.getSimpleName().equals(messageType)) {
			httpHeaders.add("IDS-Messagetype", "ids:" + ArtifactRequestMessage.class.getSimpleName());
			httpHeaders.add("IDS-Id", "https://w3id.org/idsa/autogen/" + ArtifactRequestMessage.class.getSimpleName()
					+ "/" + UUID.randomUUID());
			httpHeaders.add("IDS-RequestedArtifact",
					proxyRequest.getRequestedArtifact() != null ? proxyRequest.getRequestedArtifact()
							: UtilMessageService.REQUESTED_ARTIFACT.toString());
		} else if (ContractRequestMessage.class.getSimpleName().equals(messageType)) {
			httpHeaders.add("IDS-Messagetype", "ids:" + ContractRequestMessage.class.getSimpleName());
			httpHeaders.add("IDS-Id", "https://w3id.org/idsa/autogen/" + ContractRequestMessage.class.getSimpleName()
					+ "/" + UUID.randomUUID());
		} else if (ContractAgreementMessage.class.getSimpleName().equals(messageType)) {
			httpHeaders.add("IDS-Messagetype", "ids:" + ContractAgreementMessage.class.getSimpleName());
			httpHeaders.add("IDS-Id", "https://w3id.org/idsa/autogen/" + ContractAgreementMessage.class.getSimpleName()
					+ "/" + UUID.randomUUID());
		} else if (DescriptionRequestMessage.class.getSimpleName().equals(messageType)) {
			httpHeaders.add("IDS-Messagetype", "ids:" + DescriptionRequestMessage.class.getSimpleName());
			httpHeaders.add("IDS-Id", "https://w3id.org/idsa/autogen/" + DescriptionRequestMessage.class.getSimpleName()
					+ "/" + UUID.randomUUID());
			httpHeaders.add("IDS-RequestedElement", proxyRequest.getRequestedElement());
		}

		httpHeaders.add("IDS-ModelVersion", UtilMessageService.MODEL_VERSION);
		if (StringUtils.isNoneBlank(proxyRequest.getTransferContract())) {
			httpHeaders.add("IDS-TransferContract",
					proxyRequest.getTransferContract() != null ? proxyRequest.getTransferContract()
							: UtilMessageService.TRANSFER_CONTRACT.toString());
		}
		httpHeaders.add("IDS-Issued", DateUtil.now().toXMLFormat());
		httpHeaders.add("IDS-IssuerConnector", issueConnector);
		httpHeaders.add("IDS-SenderAgent", issueConnector);
		httpHeaders.add("IDS-CorrelationMessage", "http://correlationMessage");

		httpHeaders.add("IDS-SecurityToken-Type", "ids:DynamicAttributeToken");
		httpHeaders.add("IDS-SecurityToken-Id", "https://w3id.org/idsa/autogen/" + UUID.randomUUID());
		httpHeaders.add("IDS-SecurityToken-TokenFormat", TokenFormat.JWT.getId().toString());
		httpHeaders.add("IDS-SecurityToken-TokenValue", UtilMessageService.TOKEN_VALUE);

		return httpHeaders;
	}

	private void logResponse(ResponseEntity<String> resp) {
		logger.info("Response received with status code {}", resp.getStatusCode());
		logger.info("Response headers\n{}", resp.getHeaders());
		logger.info("Response body\n{}", resp.getBody());
	}

	// TODO should we move this method to separate class?
	private String saveFileToDisk(String responseMessage, Message requestMessage) throws IOException {
		MultipartMessage response = MultipartMessageProcessor.parseMultipartMessage(responseMessage);
		Message responseMsg = response.getHeaderContent();

		String requestedArtifact = null;
		if (requestMessage instanceof ArtifactRequestMessage && responseMsg instanceof ArtifactResponseMessage) {
			String payload = response.getPayloadContent();
			String reqArtifact = ((ArtifactRequestMessage) requestMessage).getRequestedArtifact().getPath();
			// get resource from URI http://w3id.org/engrd/connector/artifact/ +
			// requestedArtifact
			requestedArtifact = reqArtifact.substring(reqArtifact.lastIndexOf('/') + 1);
			String dataLake = dataLakeDirectory + FileSystems.getDefault().getSeparator() + requestedArtifact;
			logger.info("About to save file " + dataLake);
			recreateFileService.recreateTheFile(payload, new File(dataLake));
			logger.info("File saved");
		} else {
			logger.info("Did not have ArtifactRequestMessage and ResponseMessage - nothing to save");
			requestedArtifact = null;
		}
		return requestedArtifact;
	}

	@Override
	public ResponseEntity<String> proxyWSSRequest(ProxyRequest proxyRequest) {
		String forwardToInternal = proxyRequest.getForwardToInternal();
		String forwardTo = proxyRequest.getForwardTo();

		if (StringUtils.isEmpty(forwardTo) || StringUtils.isEmpty(forwardToInternal)) {
			return ResponseEntity.badRequest().body("Missing required fields Forward-To or Forward-To-Internal");
		}

		FileRecreatorBeanExecutor.getInstance().setForwardTo(forwardTo);
		String responseMessage = null;
		try {
			responseMessage = WebSocketClientManager.getMessageWebSocketSender()
					.sendMultipartMessageWebSocketOverHttps(proxyRequest.getMessageType(), proxyRequest.getPayload(),
							forwardToInternal);
		} catch (Exception exc) {
			logger.error("Error while processing request {}", exc);
			throw new ResponseStatusException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"Error while processing request, check logs for more details",
					exc);
		}

		return ResponseEntity.ok(responseMessage);
	}

	@Override
	public ResponseEntity<?> proxyCreationEntityForm(HttpHeaders orionHeaders, String body, URI uri)
			throws URISyntaxException {

		logger.info("ENG: Orion httpHeaders            {}", orionHeaders);
		logger.info("Forwarding form POST request to {}", uri.toString());
		HttpEntity<String> requestEntity = new HttpEntity<>(body, orionHeaders);
		logger.debug("ENG: requestEntity              {}", requestEntity);

		logger.info("ENG: calling restTemplate.exchange()... {}", uri);
		ResponseEntity<String> resp = null;
		try {
			resp = restTemplate.exchange(uri, HttpMethod.POST, requestEntity,
					String.class);
		} catch (org.springframework.web.client.HttpClientErrorException e) {
			if (e.getStatusCode() == HttpStatus.CONFLICT) {
				throw new ConflictOrionException();
			}
		}

		logger.info("ENG: calling logResponse()...");
		logResponse(resp);

		return resp;
	}
}
