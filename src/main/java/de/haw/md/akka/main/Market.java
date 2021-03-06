package de.haw.md.akka.main;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import de.haw.md.akka.main.msg.CompanyShareMsgModel;
import de.haw.md.akka.main.msg.MarketResponseMsgModel;
import de.haw.md.akka.main.msg.MarketShareMsgModel;
import de.haw.md.akka.main.msg.ResourceMsgModel;
import de.haw.md.sups.ResourceCalc;
import de.haw.md.sups.Resources;
import de.haw.md.sups.StaticVariables;

public class Market extends UntypedActor {

	private ActorRef mediator = DistributedPubSub.get(getContext().system()).mediator();

	private String channel;

	private Resources res = new Resources();

	private Map<String, BigDecimal> companyMarketPrices = new HashMap<>();

	private Map<String, MarketResponseMsgModel> mobileMarketResponses = new HashMap<>();

	private Map<String, ResourceMsgModel> resourceMarketResponses = new HashMap<>();

	private BigDecimal counter = BigDecimal.ZERO;

	private BigDecimal currentMarketVolume;

	private MarketShareMsgModel msmm;

	/**
	 * Kunstruktor! Initialisiert den Markt, liest historische Rohstoffpreise
	 * und berechnet das statische Marktvolumen
	 * 
	 * @param channel
	 */
	public Market(String channel) {
		res.readAllPrices();
		this.channel = channel;
		currentMarketVolume = StaticVariables.MARKET_VOLUME;
		MarketContainer.getInstance().setMarket(this);
	}

	/*
	 * Diese Methode reagiert auf ankommende Nachrichten, sollte die Nachricht
	 * "Tick" sein, so werden die aktuellen Rohstoffpreise ver�ffentlicht und
	 * die aktuellen Marktvolumina.
	 * 
	 * (non-Javadoc)
	 * 
	 * @see akka.actor.UntypedActor#onReceive(java.lang.Object)
	 */
	@Override
	public void onReceive(Object msg) throws Exception {
		if (msg instanceof String) {
			if (msg.equals("Tick")) {
				publishResources();
				calculateMarketVolumeAShares();
			} else {
				handleMarketResponse(msg, new ObjectMapper());
				handleResourceResponse(msg, new ObjectMapper());
			}
		} else {
			unhandled(msg);
		}
	}

	/**
	 * Speichert die Rohstoff-Nachrichten ab. Diese werden dann von der GUI
	 * ausgelesen.
	 * 
	 * @param msg
	 * @param om
	 * @throws IOException
	 * @throws JsonParseException
	 * @throws JsonMappingException
	 */
	private void handleResourceResponse(Object msg, ObjectMapper om) throws IOException, JsonParseException, JsonMappingException {
		try {
			ResourceMsgModel rmm = om.readValue((String) msg, ResourceMsgModel.class);
			if (resourceMarketResponses.containsKey(rmm.getType()))
				resourceMarketResponses.replace(rmm.getType(), rmm);
			else
				resourceMarketResponses.put(rmm.getType(), rmm);
		} catch (UnrecognizedPropertyException e) {
		}
	}

	/**
	 * Speichert die Markt-Nachrichten ab. Diese werden dann von der GUI
	 * ausgelesen.
	 * 
	 * @param msg
	 * @param om
	 * @throws IOException
	 * @throws JsonParseException
	 * @throws JsonMappingException
	 */
	private void handleMarketResponse(Object msg, ObjectMapper om) throws IOException, JsonParseException, JsonMappingException {
		try {
			MarketResponseMsgModel mrmm = om.readValue((String) msg, MarketResponseMsgModel.class);
			if (mrmm.getType().equals("Mobile_Phone")) {
				if (companyMarketPrices.containsKey(mrmm.getCompany())) {
					companyMarketPrices.replace(mrmm.getCompany(), StaticVariables.convertToBigDecimal(mrmm.getRevenue()));
				} else {
					companyMarketPrices.put(mrmm.getCompany(), StaticVariables.convertToBigDecimal(mrmm.getRevenue()));
				}
				if (mobileMarketResponses.containsKey(mrmm.getCompany())) {
					mobileMarketResponses.replace(mrmm.getCompany(), mrmm);
				} else {
					mobileMarketResponses.put(mrmm.getCompany(), mrmm);
				}
			}
			// System.out.println(msg);
			publish((String) msg);
		} catch (UnrecognizedPropertyException e) {
		}
	}

	/**
	 * Das Marktvolumen wird an dieser Stelle berechnet. Volume =
	 * Gesammt_Markt_Volume / (Z�hler / (l�nge_des_Monats))
	 * 
	 * Anschliessend werden die Marktanteile der einzelnen Unternehmen
	 * berechnet.
	 * 
	 * @throws JsonGenerationException
	 * @throws JsonMappingException
	 * @throws IOException
	 * @throws CloneNotSupportedException
	 */
	private void calculateMarketVolumeAShares() throws JsonGenerationException, JsonMappingException, IOException, CloneNotSupportedException {
		if (counter.compareTo(BigDecimal.ZERO) != 0) {
			final BigDecimal volume = StaticVariables.MARKET_VOLUME.divide(counter.divide(StaticVariables.MONTH, 10, RoundingMode.HALF_UP), 0,
					RoundingMode.HALF_DOWN);
			if (volume.compareTo(StaticVariables.MARKET_VOLUME) > 0)
				currentMarketVolume = StaticVariables.MARKET_VOLUME;
			else
				currentMarketVolume = volume;
		}
		if (companyMarketPrices.size() > 0) {
			final String generateShares = generateShares();
			publish(generateShares);
		}
		counter = counter.add(BigDecimal.ONE);
	}

	/**
	 * Hier werden die Rohstoffe im Markt ver�ffentlicht
	 * 
	 * @throws JsonGenerationException
	 * @throws JsonMappingException
	 * @throws IOException
	 */
	private void publishResources() throws JsonGenerationException, JsonMappingException, IOException {
		final String resOilMsg = mapToJson("Oil", res.getOilPrice());
		publish(resOilMsg);
		final String resKupferMsg = mapToJson("Kupfer", res.getKupferPrice());
		publish(resKupferMsg);
		final String resAluminiumMsg = mapToJson("Aluminium", res.getAluminiumPrice());
		publish(resAluminiumMsg);
		final String resGoldMsg = mapToJson("Gold", res.getGoldPrice());
		publish(resGoldMsg);
		final String resNickelMsg = mapToJson("Nickel", res.getNickelPrice());
		publish(resNickelMsg);
		final String resPalladiumMsg = mapToJson("Palladium", res.getPalladiumPrice());
		publish(resPalladiumMsg);
		final String resPlatinMsg = mapToJson("Platin", res.getPlatinPrice());
		publish(resPlatinMsg);
		final String resSilberMsg = mapToJson("Silber", res.getSilberPrice());
		publish(resSilberMsg);
		final String resZinnMsg = mapToJson("Zinn", res.getZinnPrice());
		publish(resZinnMsg);
	}

	/**
	 * Hier werden die Marktanteile der Unternehmen berechnet. Dabei bestehen
	 * die Marktanteile aus einem fixen und einem variablen Teil.
	 * 
	 * @return
	 * @throws JsonGenerationException
	 * @throws JsonMappingException
	 * @throws IOException
	 * @throws CloneNotSupportedException
	 */
	private String generateShares() throws JsonGenerationException, JsonMappingException, IOException, CloneNotSupportedException {
		ObjectMapper om = new ObjectMapper();
		// Fixe Anteile pro Unternehmen = FIXED_MARKET_SHARE /
		// Anzahl_der_Unternehmen
		BigDecimal fixedMarketSharePerCompany = StaticVariables.FIXED_MARKET_SHARE.divide(new BigDecimal(companyMarketPrices.size()), RoundingMode.HALF_DOWN);
		BigDecimal sumPrice = BigDecimal.ZERO;
		// Variabler Anteil in Prozent = 100 - FIXED_MARKET_SHARE
		BigDecimal variableShare = StaticVariables.HUNDRED.subtract(StaticVariables.FIXED_MARKET_SHARE);
		List<CompanyShareMsgModel> companyShareMsgModels = new ArrayList<>();
		// Alle Preise von Unternehmen werden auf addiert. Damit der prozentuale
		// Anteil am Preis bestimmt werden kann.
		for (String company : companyMarketPrices.keySet())
			sumPrice = sumPrice.add(companyMarketPrices.get(company));
		for (String company : companyMarketPrices.keySet()) {
			// Berechnung erfolgt nur wenn ein Unternehmen noch am Markt ist, also Verkaufspreis > 0
			if (companyMarketPrices.get(company).compareTo(BigDecimal.ZERO) != 0) {
				// sumPriceOnePercent = sumPrice / 100 
				BigDecimal sumPriceOnePercent = sumPrice.divide(StaticVariables.HUNDRED, 10, RoundingMode.HALF_DOWN);
				// percentPerPrice = companyMarketPrice / sumPriceOnePercent
				BigDecimal percentPerPrice = companyMarketPrices.get(company).divide(sumPriceOnePercent, 10, RoundingMode.HALF_DOWN);
				// 
				BigDecimal variableShareOnePercent = variableShare.divide(StaticVariables.HUNDRED, 10, RoundingMode.HALF_DOWN);
				BigDecimal variableSharePerComp = percentPerPrice.multiply(variableShareOnePercent);
				BigDecimal perCompMarketShare = (fixedMarketSharePerCompany.add(variableSharePerComp));
				companyShareMsgModels.add(new CompanyShareMsgModel(company, perCompMarketShare.toString()));
			}
		}
		companyShareMsgModels = sortShares(companyShareMsgModels);
		calculateShareVolume(companyShareMsgModels);
		MarketShareMsgModel msmm = new MarketShareMsgModel();
		msmm.setType("Market_Share");
		msmm.setCompanyShareMsgModels(companyShareMsgModels);
		msmm.setDate(ResourceCalc.getFinalDate(res.getOilPrice()).toString(StaticVariables.DE_DATE_FORMATTER));
		this.msmm = msmm;
		return om.writeValueAsString(msmm);
	}

	private void calculateShareVolume(List<CompanyShareMsgModel> companyShareMsgModels) {
		for (CompanyShareMsgModel companyShareMsgModel : companyShareMsgModels)
			companyShareMsgModel.setShareVolume(currentMarketVolume.divide(StaticVariables.HUNDRED, RoundingMode.HALF_DOWN)
					.multiply(StaticVariables.convertToBigDecimal(companyShareMsgModel.getShareValue())).setScale(0, RoundingMode.HALF_DOWN).toString());
	}

	private List<CompanyShareMsgModel> sortShares(List<CompanyShareMsgModel> companyShareMsgModels) throws CloneNotSupportedException {
		Collections.sort(companyShareMsgModels);
		CompanyShareMsgModel[] csmmArray = new CompanyShareMsgModel[companyShareMsgModels.size()];
		for (int i = 0; i < companyShareMsgModels.size(); i++) {
			csmmArray[i] = (CompanyShareMsgModel) companyShareMsgModels.get(i).clone();
		}
		Arrays.sort(csmmArray, Collections.reverseOrder());
		for (int i = 0; i < csmmArray.length; i++)
			csmmArray[i].setShareValue(companyShareMsgModels.get(i).getShareValue());
		List<CompanyShareMsgModel> newCompanyShareMsgModels = new ArrayList<>();
		for (int i = 0; i < csmmArray.length; i++) {
			newCompanyShareMsgModels.add(csmmArray[i]);
		}
		return newCompanyShareMsgModels;
	}

	private void publish(String msg) {
		mediator.tell(new DistributedPubSubMediator.Publish(channel, msg), getSelf());
	}

	public String mapToJson(String type, Map<DateTime, BigDecimal> ressource) throws JsonGenerationException, JsonMappingException, IOException {
		ObjectMapper mapper = new ObjectMapper();
		ResourceMsgModel rmm = new ResourceMsgModel();
		final BigDecimal newPrice = ResourceCalc.nextRandomStockPrice(ressource);
		final DateTime date = ResourceCalc.getFinalDate(ressource).plusDays(1);
		rmm.setDate(date.toString(StaticVariables.DE_DATE_FORMATTER));
		rmm.setValue(newPrice.setScale(2, RoundingMode.HALF_DOWN).toString());
		rmm.setType(type);
		ressource.put(date, newPrice);
		addResourceMsg(rmm);
		return mapper.writeValueAsString(rmm);
	}

	private void addResourceMsg(ResourceMsgModel rmm) {
		if (resourceMarketResponses.containsKey(rmm.getType()))
			resourceMarketResponses.replace(rmm.getType(), rmm);
		else
			resourceMarketResponses.put(rmm.getType(), rmm);
	}

	public Map<String, BigDecimal> getCompanyMarketPrices() {
		return companyMarketPrices;
	}

	public BigDecimal getCurrentMarketVolume() {
		return currentMarketVolume;
	}

	public Map<String, MarketResponseMsgModel> getMobileMarketResponses() {
		return mobileMarketResponses;
	}

	public Map<String, ResourceMsgModel> getResourceMarketResponses() {
		return resourceMarketResponses;
	}

	public MarketShareMsgModel getMsmm() {
		return msmm;
	}

}
