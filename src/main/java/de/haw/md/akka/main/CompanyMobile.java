package de.haw.md.akka.main;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.joda.time.DateTime;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import de.haw.md.akka.main.msg.CompanyShareMsgModel;
import de.haw.md.akka.main.msg.MarketResponseMsgModel;
import de.haw.md.akka.main.msg.MarketShareMsgModel;
import de.haw.md.akka.main.msg.ResourceMsgModel;
import de.haw.md.sups.StaticVariables;

public class CompanyMobile extends UntypedActor {

	private String channel;

	private String nameOfSubscriber;

	private String supplier;
	private BigDecimal supDiscount;

	private BigDecimal kupferPrice;
	private BigDecimal aluminiumPrice;
	private BigDecimal goldPrice;
	private BigDecimal nickelPrice;
	private BigDecimal palladiumPrice;
	private BigDecimal platinPrice;
	private BigDecimal silberPrice;
	private BigDecimal zinnPrice;
	private BigDecimal plasticPrice;
	private BigDecimal electronicPartPrice;

	private static final BigDecimal POP_PLASTIC_IN_G = StaticVariables.convertToBigDecimal("55.9");
	private static final BigDecimal POP_KUPFER_IN_G = StaticVariables.convertToBigDecimal("7.67");
	private static final BigDecimal POP_ALUMINIUM_IN_G = StaticVariables.convertToBigDecimal("1.3");
	private static final BigDecimal POP_NICKEL_IN_G = StaticVariables.convertToBigDecimal("1.3");
	private static final BigDecimal POP_ZINN_IN_G = StaticVariables.convertToBigDecimal("1.3");
	private static final BigDecimal POP_GOLD_IN_G = StaticVariables.convertToBigDecimal("0.004");
	private static final BigDecimal POP_SILBER_IN_G = StaticVariables.convertToBigDecimal("0.05");
	private static final BigDecimal POP_PLATIN_IN_G = StaticVariables.convertToBigDecimal("0.004");
	private static final BigDecimal POP_PALLADIUM_IN_G = StaticVariables.convertToBigDecimal("0.002");

	private BigDecimal costManHour;
	private BigDecimal prodManHour;
	private BigDecimal bonus;
	private BigDecimal fixCost;
	private BigDecimal productionLines;
	private BigDecimal productionLineCapacity;
	private BigDecimal monthlyCosts;

	private BigDecimal shareVolume = StaticVariables.ESTIMATED_MARKT_VOLUME.divide(StaticVariables.MONTH.subtract(new BigDecimal("15")), 0,
			RoundingMode.HALF_DOWN);
	private BigDecimal selledProducts;

	private BigDecimal basisPrice = BigDecimal.ZERO;

	private DateTime dateTicker;
	private DateTime nextMonthTicker;

	private BigDecimal prodPrice;

	private BigDecimal completeProfit = BigDecimal.ZERO;

	public CompanyMobile(String channel, String nameOfSubscriber, BigDecimal costManHour, BigDecimal prodManHour, BigDecimal bonus, String supplier,
			BigDecimal supDiscount, BigDecimal fixCost, BigDecimal productionLines, BigDecimal productionLineCapacity, BigDecimal monthlyCosts) {
		this.supDiscount = supDiscount;
		this.supplier = supplier;
		this.costManHour = costManHour;
		this.fixCost = fixCost;
		this.productionLines = productionLines;
		this.productionLineCapacity = productionLineCapacity;
		this.prodManHour = prodManHour;
		this.bonus = bonus;
		this.nameOfSubscriber = nameOfSubscriber;
		this.channel = channel;
		this.monthlyCosts = monthlyCosts;
		ActorRef mediator = DistributedPubSub.get(getContext().system()).mediator();
		mediator.tell(new DistributedPubSubMediator.Subscribe(channel, getSelf()), getSelf());
	}

	@Override
	public void onReceive(Object msg) throws Exception {
		if (msg instanceof String) {
			ObjectMapper om = new ObjectMapper();
			try {
				ResourceMsgModel rmm = om.readValue((String) msg, ResourceMsgModel.class);
				setResourcePrices(rmm);
			} catch (UnrecognizedPropertyException e) {
			}
			try {
				MarketShareMsgModel msmm = om.readValue((String) msg, MarketShareMsgModel.class);
				shareVolume = setShareVolume(findCompanyShares(msmm)).divide(StaticVariables.MONTH, 0, RoundingMode.HALF_DOWN);
			} catch (UnrecognizedPropertyException e) {
			}
			if (((String) msg).contains("company")) {
				try {
					MarketResponseMsgModel mrmmInput = om.readValue((String) msg, MarketResponseMsgModel.class);
					setPlasticPrices(mrmmInput);
					if (mrmmInput.getCompany().equals(supplier))
						setPlasticPrices(mrmmInput);
					if (dateTicker == null) {
						dateTicker = DateTime.parse(mrmmInput.getDate(), StaticVariables.DE_DATE_FORMATTER);
						nextMonthTicker = dateTicker.plusMonths(1);
					}
					if (pricesNotNull() && shareVolume.compareTo(BigDecimal.ZERO) != 0) {
						prodPrice = calculateProdPrice(shareVolume);
						if (basisPrice.compareTo(BigDecimal.ZERO) == 0)
							basisPrice = prodPrice.multiply(bonus);
						MarketResponseMsgModel mrmm = new MarketResponseMsgModel();
						mrmm.setCompany(nameOfSubscriber);
						mrmm.setType("Mobile_Phone");
						mrmm.setDate(om.readValue((String) msg, MarketResponseMsgModel.class).getDate());
						final BigDecimal revenuePerMobile = basisPrice.setScale(2, RoundingMode.UP);
						mrmm.setRevenue(revenuePerMobile.toString());
						mrmm.setSelledProducts(selledProducts.toString());
						BigDecimal profit = (revenuePerMobile.subtract(prodPrice)).multiply(selledProducts).setScale(2, RoundingMode.HALF_UP);
						if (profit.compareTo(BigDecimal.ZERO) < 0) {
							BigDecimal newRevenue = recalculatePrice();
							if (newRevenue.compareTo(BigDecimal.ZERO) <= 0) {
								mrmm.setRevenue(BigDecimal.ZERO.toString());
								profit = BigDecimal.ZERO;

							} else {
								mrmm.setRevenue(newRevenue.setScale(2, RoundingMode.HALF_DOWN).toString());
								profit = (newRevenue.subtract(prodPrice)).multiply(selledProducts).setScale(2, RoundingMode.HALF_UP);
							}
						}
						if (dateTicker.isBefore(DateTime.parse(mrmmInput.getDate(), StaticVariables.DE_DATE_FORMATTER))) {
							if (nextMonthTicker.isBefore(dateTicker)) {
								mrmm.setProfit(profit.subtract(monthlyCosts).toString());
								nextMonthTicker = nextMonthTicker.plusMonths(1);
							} else
								mrmm.setProfit(profit.toString());
							completeProfit = completeProfit.add(StaticVariables.convertToBigDecimal(mrmm.getProfit()));
							dateTicker = DateTime.parse(mrmmInput.getDate(), StaticVariables.DE_DATE_FORMATTER);
							if (completeProfit.compareTo(StaticVariables.HIGHEST_ACCEPTEBLE_DEFICIT) <= 0) {
								mrmm.setRevenue(BigDecimal.ZERO.toString());
								mrmm.setProfit(BigDecimal.ZERO.toString());
							}
							if (StaticVariables.convertToBigDecimal(mrmm.getRevenue()).compareTo(BigDecimal.ZERO) != 0)
								mrmm.setProductionCost(prodPrice.setScale(2, RoundingMode.HALF_UP).toString());
							else
								mrmm.setProductionCost(BigDecimal.ZERO.toString());
							ActorRef publisher = MarketContainer.getInstance().getPublisher(channel);
							publisher.tell(om.writeValueAsString(mrmm), getSelf());
						}
					}
				} catch (UnrecognizedPropertyException e) {
				}
			}
		}
	}

	private BigDecimal recalculatePrice() {
		BigDecimal newPrice = basisPrice;
		BigDecimal adjustment = StaticVariables.PRICE_ADJUSTMENT;
		do {
			prodPrice = calculateProdPrice(shareVolume.multiply(BigDecimal.ONE.add(adjustment)));
			newPrice = basisPrice.divide(BigDecimal.ONE.add(adjustment), 10, RoundingMode.HALF_UP);
			if (newPrice.subtract(prodPrice).compareTo(BigDecimal.ZERO) > 0) {
				return newPrice;
			}
			adjustment = adjustment.add(StaticVariables.PRICE_ADJUSTMENT);
		} while (newPrice.compareTo(prodPrice.multiply(new BigDecimal("1.02"))) > 0 || adjustment.compareTo(bonus.multiply(new BigDecimal(2))) <= 0);
		return BigDecimal.ZERO;
	}

	private CompanyShareMsgModel findCompanyShares(MarketShareMsgModel msmm) {
		for (CompanyShareMsgModel csmm : msmm.getCompanyShareMsgModels())
			if (csmm.getCompany().equals(nameOfSubscriber))
				return csmm;
		return null;
	}

	private BigDecimal setShareVolume(CompanyShareMsgModel csmm) {
		if (csmm != null)
			return new BigDecimal(csmm.getShareVolume());
		return BigDecimal.ZERO;
	}

	private BigDecimal calculateProdPrice(BigDecimal shareVolume) {
		final BigDecimal platinPartPrice = platinPrice.divide(StaticVariables.OZ_TO_GRAMM, RoundingMode.HALF_DOWN).multiply(POP_PLATIN_IN_G);
		final BigDecimal goldPartPrice = goldPrice.divide(StaticVariables.OZ_TO_GRAMM, RoundingMode.HALF_DOWN).multiply(POP_GOLD_IN_G);
		final BigDecimal silberPartPrice = silberPrice.divide(StaticVariables.OZ_TO_GRAMM, RoundingMode.HALF_DOWN).multiply(POP_SILBER_IN_G);
		final BigDecimal palladiumPartPrice = palladiumPrice.divide(StaticVariables.OZ_TO_GRAMM, RoundingMode.HALF_DOWN).multiply(POP_PALLADIUM_IN_G);
		final BigDecimal plasticPartPrice = plasticPrice.divide(StaticVariables.KG_IN_GRAMM, RoundingMode.HALF_DOWN).multiply(POP_PLASTIC_IN_G);
		final BigDecimal kupferPartPrice = kupferPrice.divide(StaticVariables.T_IN_GRAMM, RoundingMode.HALF_DOWN).multiply(POP_KUPFER_IN_G);
		final BigDecimal aluminiumPartPrice = aluminiumPrice.divide(StaticVariables.T_IN_GRAMM, RoundingMode.HALF_DOWN).multiply(POP_ALUMINIUM_IN_G);
		final BigDecimal nickelPartPrice = nickelPrice.divide(StaticVariables.T_IN_GRAMM, RoundingMode.HALF_DOWN).multiply(POP_NICKEL_IN_G);
		final BigDecimal zinnPartPrice = zinnPrice.divide(StaticVariables.T_IN_GRAMM, RoundingMode.HALF_DOWN).multiply(POP_ZINN_IN_G);
		final BigDecimal compPartWOSupPrice = platinPartPrice.add(goldPartPrice).add(silberPartPrice).add(palladiumPartPrice).add(plasticPartPrice)
				.add(kupferPartPrice).add(aluminiumPartPrice).add(nickelPartPrice).add(zinnPartPrice);
		final BigDecimal supPriceWithDisc = electronicPartPrice.divide(supDiscount, RoundingMode.HALF_UP);
		final BigDecimal complManCost = costManHour.divide(prodManHour, RoundingMode.HALF_DOWN);
		final BigDecimal numberOfProdLines = shareVolume.divide(productionLineCapacity, 0, RoundingMode.UP);
		BigDecimal prodLinesCost;
		if (numberOfProdLines.compareTo(productionLines) > 0) {
			selledProducts = productionLines.multiply(productionLineCapacity).setScale(0, RoundingMode.UP);
			prodLinesCost = fixCost.divide(selledProducts, 2, RoundingMode.HALF_UP);
		} else {
			selledProducts = shareVolume;
			final BigDecimal costOfOne = fixCost.divide(productionLines, 2, RoundingMode.HALF_UP);
			prodLinesCost = costOfOne.multiply(numberOfProdLines).divide(selledProducts, 2, RoundingMode.HALF_UP);
		}
		return (compPartWOSupPrice.add(complManCost).add(supPriceWithDisc)).add(prodLinesCost);
	}

	private boolean pricesNotNull() {
		if (kupferPrice == null)
			return false;
		if (aluminiumPrice == null)
			return false;
		if (goldPrice == null)
			return false;
		if (nickelPrice == null)
			return false;
		if (palladiumPrice == null)
			return false;
		if (plasticPrice == null)
			return false;
		if (platinPrice == null)
			return false;
		if (silberPrice == null)
			return false;
		if (zinnPrice == null)
			return false;
		if (electronicPartPrice == null)
			return false;
		return true;
	}

	private void setPlasticPrices(MarketResponseMsgModel mrmm) {
		if (mrmm.getType().equals("Plastic"))
			plasticPrice = StaticVariables.convertToBigDecimal(mrmm.getRevenue());
		if (mrmm.getType().equals("Electronic_Part"))
			electronicPartPrice = StaticVariables.convertToBigDecimal(mrmm.getRevenue());
	}

	private void setResourcePrices(ResourceMsgModel rmm) {
		switch (rmm.getType()) {
		case "Kupfer":
			kupferPrice = StaticVariables.convertToBigDecimal(rmm.getValue());
			break;
		case "Aluminium":
			aluminiumPrice = StaticVariables.convertToBigDecimal(rmm.getValue());
			break;
		case "Gold":
			goldPrice = StaticVariables.convertToBigDecimal(rmm.getValue());
			break;
		case "Nickel":
			nickelPrice = StaticVariables.convertToBigDecimal(rmm.getValue());
			break;
		case "Palladium":
			palladiumPrice = StaticVariables.convertToBigDecimal(rmm.getValue());
			break;
		case "Platin":
			platinPrice = StaticVariables.convertToBigDecimal(rmm.getValue());
			break;
		case "Silber":
			silberPrice = StaticVariables.convertToBigDecimal(rmm.getValue());
			break;
		case "Zinn":
			zinnPrice = StaticVariables.convertToBigDecimal(rmm.getValue());
			break;
		}

	}

}
