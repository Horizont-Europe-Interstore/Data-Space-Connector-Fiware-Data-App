package it.eng.idsa.dataapp.web.rest;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.google.gson.Gson;

import de.fraunhofer.iais.eis.Message;
import it.eng.idsa.dataapp.handler.ArtifactMessageHandler;
import it.eng.idsa.dataapp.handler.DataAppMessageHandler;
import it.eng.idsa.dataapp.handler.MessageHandlerFactory;
import it.eng.idsa.dataapp.model.OrionRequest;
import it.eng.idsa.dataapp.service.OrionContextBrokerService;
import it.eng.idsa.dataapp.util.ConstantUtil;
import it.eng.idsa.dataapp.util.MessageUtil;
import it.eng.idsa.multipart.processor.MultipartMessageProcessor;

@RestController
@ConditionalOnProperty(name = "application.dataapp.http.config", havingValue = "form")
public class DataControllerBodyForm {
	private static final Logger logger = LoggerFactory.getLogger(DataControllerBodyForm.class);

	@Value("${application.fiware.contextpath.orionprovider}")
	private String contextPathOrionProvider;

	private MessageUtil messageUtil;
	private MessageHandlerFactory factory;

	private OrionContextBrokerService orionService;

	public DataControllerBodyForm(MessageUtil messageUtil, MessageHandlerFactory factory,
			OrionContextBrokerService orionService) {
		this.messageUtil = messageUtil;
		this.factory = factory;
		this.orionService = orionService;

	}

	/**
	 * Multipart/form controller handler
	 * 
	 * @param httpHeaders  Http header
	 * @param header       Multipart header part
	 * @param responseType response type
	 * @param payload      Could be MultipartFile or plain String
	 * @return Response entity
	 * @throws UnsupportedOperationException UnsupportedOperationException
	 * @throws IOException                   IOException
	 */
	@PostMapping(value = "/data")
	public ResponseEntity<?> routerForm(@RequestHeader HttpHeaders httpHeaders,
			@RequestParam(value = "header") String header,
			@RequestHeader(value = "Response-Type", required = false) String responseType,
			@RequestParam(value = "payload", required = false) Object payload)
			throws URISyntaxException, UnsupportedOperationException, IOException {

		logger.info("Multipart/form request");

		// Received "header" and "payload"
		logger.info("header" + header);
		logger.info("headers=" + httpHeaders);
		if (payload != null) {
			logger.info("ENG: payload lenght = " + payload.toString().length());
			logger.info("ENG: payload = " + payload.toString());
		} else {
			logger.info("Payload is empty");
		}

		if (payload instanceof MultipartFile) {
			MultipartFile file = (MultipartFile) payload;
			try (FileOutputStream fos = new FileOutputStream(file.getOriginalFilename())) {
				byte[] decoder = Base64.getDecoder().decode(file.getBytes());
				fos.write(decoder);
			}
		}

		Message message = MultipartMessageProcessor.getMessage(header);

		// Create handler based on type of message and get map with header and payload
		DataAppMessageHandler handler = factory.createMessageHandler(message.getClass());
		Map<String, Object> responseMap = handler.handleMessage(message, payload);
		Object responseHeader = responseMap.get(DataAppMessageHandler.HEADER);
		Object responsePayload = responseMap.get(DataAppMessageHandler.PAYLOAD);
		ContentType payloadContentType = ContentType.TEXT_PLAIN;
		if (responsePayload != null && messageUtil.isValidJSON(responsePayload.toString())) {
			payloadContentType = ContentType.APPLICATION_JSON;
		}

		// check payload is orion request
		OrionRequest orionRequest = null;

		if (handler instanceof ArtifactMessageHandler &&
				payload != null && payload instanceof String) {
			try {
				orionRequest = new Gson().fromJson((String) payload, OrionRequest.class);
			} catch (Exception e) {
				logger.info("payload is not OrionRequest");
			}
			if (orionRequest != null && orionRequest.getEntityId() != null &&
					orionRequest.getOrigin() != null &&
					orionRequest.getOrigin().equals(ConstantUtil.ORION_ORIGIN)) {
				responsePayload = orionService
						.GetEntity(orionRequest);
				payloadContentType = ContentType.APPLICATION_JSON;
			}
		}
		// prepare body response - multipart message.
		HttpEntity resultEntity = messageUtil.createMultipartMessageForm(
				MultipartMessageProcessor.serializeToJsonLD(responseHeader),
				responsePayload != null ? responsePayload.toString() : null, payloadContentType);


		ByteArrayOutputStream outStream = new ByteArrayOutputStream();
		resultEntity.writeTo(outStream);
		outStream.flush();
			
		return ResponseEntity.ok().header("foo", "bar")
				.contentType(MediaType.parseMediaType(resultEntity.getContentType().getValue()))
				.body(outStream.toString());
	}
}
