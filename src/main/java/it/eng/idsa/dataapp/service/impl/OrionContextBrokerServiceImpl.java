package it.eng.idsa.dataapp.service.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;

import it.eng.idsa.dataapp.model.OrionRequest;
import it.eng.idsa.dataapp.model.RequestContract;
import it.eng.idsa.dataapp.service.OrionContextBrokerService;
import it.eng.idsa.dataapp.util.ConstantUtil;

@Service
public class OrionContextBrokerServiceImpl implements OrionContextBrokerService {

	@Value("${application.mongo.host}")
	private String mongoHost;

	@Value("${application.mongo.port}")
	private String mongoPort;

	@Value("${application.mongo.userName}")
	private String mongoUsername;

	@Value("${application.mongo.password}")
	private String mongoPassword;

	@Value("${application.orion.protocol}")
	private String orionProtocol;

	@Value("${application.orion.host}")
	private String orionHost;

	@Value("${application.orion.port.registration}")
	private String orionPort;

	@Value("${application.fiware.contextpath.orionprovider}")
	private String contextPathOrionProvider;

	private static final Logger logger = LoggerFactory.getLogger(OrionContextBrokerServiceImpl.class);

	private RestTemplate restTemplate;

	public OrionContextBrokerServiceImpl(RestTemplate restTemplate) {
		this.restTemplate = restTemplate;
	}

	@Override
	public String GetEntity(OrionRequest orionRequest) throws URISyntaxException {
		var uri = new URI(orionProtocol, null, orionHost, Integer.valueOf(orionPort),
				"/" + contextPathOrionProvider + "/" + orionRequest.getEntityId(), null, null);
		logger.info("Triggering request towards {}", uri);
		ResponseEntity<String> response;
		try {
			logger.info(" ENG orionRequest.getEntityId()     {}", orionRequest.getEntityId());

			logger.info(" ENG orionURI                          {}", uri);
			response = restTemplate.exchange(uri, HttpMethod.GET, null, String.class);
		} catch (HttpStatusCodeException e) {
			if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
				return null;
			}
			throw e;
		}
		logger.info("Response received {}\n with status code {}", response.getBody(), response.getStatusCode());
		if (response != null && response.getStatusCode() == HttpStatus.OK) {
			return response.getBody();
		}
		return null;
	}

	private MongoClient getMongoClient() {
		MongoClientURI uri = new MongoClientURI(MessageFormat.format(
				"mongodb://{0}:{1}@{2}:{3}/?authSource=orion",
				mongoUsername, mongoPassword, mongoHost, mongoPort));
		return new MongoClient(uri);
	}

	private MongoDatabase getMongoDataBase(MongoClient client) {
		return client.getDatabase("orion");
	}

	public Document GetReference(String entityId) {
		MongoClient client = null;
		try {
			client = getMongoClient();
			logger.info("ENG: connecting to Database <orionHost> {} on port {}", mongoHost, mongoPort);

			MongoDatabase db = getMongoDataBase(client);
			MongoCollection<Document> collection = db.getCollection("references");

			logger.info("ENG: find element in references collections with id {}", entityId);

			var filter = Filters.eq(ConstantUtil.ENTITY, entityId);
			return collection.find(filter).first();
		} finally {
			if (client != null) {
				client.close();
			}
		}
	}

	public void StoreEntity(String entityId, String forwardTo, RequestContract requestContract, String smartContractId) {
		MongoClient client = null;
		try {
			client = getMongoClient();
			logger.info("ENG: connecting to Database <orionHost> {} on port {}", mongoHost, mongoPort);

			MongoDatabase db = getMongoDataBase(client);
			MongoCollection<Document> collection = db.getCollection("references");
			Document document = new Document();
			document.append(ConstantUtil.FORWARD_TO, forwardTo);
			document.append(ConstantUtil.ENTITY, entityId);
			document.append(ConstantUtil.TRANSFER_CONTRACT, requestContract.TransferContract);
			document.append(ConstantUtil.REQUESTED_ARTIFACT, requestContract.ContractArtifact);
			document.append(ConstantUtil.SMART_CONTRACT, smartContractId);
			logger.info("ENG: reference saved in db!");
			collection.insertOne(document);

		} finally {
			if (client != null) {
				client.close();
			}
		}
	}

}
