package net.runelite.client.plugins.realworldvalue;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.QuantityFormatter;

@Slf4j
@PluginDescriptor(
	name = "Real World Value",
	description = "Shows the real-world value of items based on bond prices",
	tags = {"gp", "gold", "usd", "value", "bond", "currency"}
)
public class RealWorldValuePlugin extends Plugin
{
	private static final int BOND_ITEM_ID = 13190; // Old School Bond
	
	// Bond prices in different currencies (as of January 2025)
	private static final double BOND_USD_PRICE = 8.99;
	private static final double BOND_GBP_PRICE = 6.49;
	private static final double BOND_EUR_PRICE = 7.99;
	private static final double BOND_CAD_PRICE = 11.49;
	private static final double BOND_AUD_PRICE = 12.99;
	private static final double BOND_NOK_PRICE = 77.00;
	private static final double BOND_SEK_PRICE = 77.00;
	private static final double BOND_BRL_PRICE = 21.99;
	private static final double BOND_PLN_PRICE = 29.99;
	private static final double BOND_SGD_PRICE = 12.99;
	private static final double BOND_DKK_PRICE = 48.99;
	
	// Minimum wage per hour (approximate, may vary by region)
	private static final double MIN_WAGE_USD = 7.25;  // US Federal
	private static final double MIN_WAGE_GBP = 11.44; // UK National Living Wage
	private static final double MIN_WAGE_EUR = 12.41; // Germany
	private static final double MIN_WAGE_CAD = 17.30; // Average across provinces
	private static final double MIN_WAGE_AUD = 23.23; // Australia
	private static final double MIN_WAGE_NOK = 150.00; // Norway (approximate)
	private static final double MIN_WAGE_SEK = 120.00; // Sweden (approximate)
	private static final double MIN_WAGE_BRL = 6.38;  // Brazil
	private static final double MIN_WAGE_PLN = 27.70; // Poland
	private static final double MIN_WAGE_SGD = 10.50; // Singapore (approximate)
	private static final double MIN_WAGE_DKK = 110.00; // Denmark (approximate)

	@Inject
	private Client client;

	@Inject
	private RealWorldValueConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private TooltipManager tooltipManager;

	private int bondPrice = -1;
	private long lastBondPriceUpdate = 0;
	private static final long BOND_PRICE_CACHE_MS = 300000; // 5 minutes
	private int lastItemId = -1;
	private boolean tooltipAdded = false;

	@Override
	protected void startUp() throws Exception
	{
		log.info("Real World Value plugin started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Real World Value plugin stopped!");
		bondPrice = -1;
		lastBondPriceUpdate = 0;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Check if player clicked "Examine" on an item
		if (!event.getMenuOption().equals("Examine"))
		{
			return;
		}

		if (!config.enabled() || !config.showMinimumWage())
		{
			return;
		}

		int itemId = event.getItemId();
		if (itemId == -1)
		{
			return;
		}

		// Update bond price if needed
		if (bondPrice <= 0 || System.currentTimeMillis() - lastBondPriceUpdate > BOND_PRICE_CACHE_MS)
		{
			updateBondPrice();
		}

		if (bondPrice <= 0)
		{
			return;
		}

		// Get item price
		int itemPrice = itemManager.getItemPrice(itemId);
		if (itemPrice <= 0)
		{
			return;
		}

		// Calculate real world value
		double realWorldValue = calculateRealWorldValue(itemPrice);
		
		// Get minimum wage for selected currency
		double minWage = getMinimumWage();
		if (minWage <= 0)
		{
			return;
		}

		// Calculate hours of work
		double hoursOfWork = realWorldValue / minWage;

		// Format the message
		String message;
		if (hoursOfWork >= 1.0)
		{
			message = String.format("This item is worth %.1f hours of minimum wage work.", hoursOfWork);
		}
		else if (hoursOfWork >= (1.0 / 60.0)) // At least 1 minute
		{
			int minutes = (int)(hoursOfWork * 60);
			message = String.format("This item is worth %d minutes of minimum wage work.", minutes);
		}
		else // Less than 1 minute
		{
			double seconds = hoursOfWork * 3600;
			
			// For very small values, show decimal places
			if (seconds >= 1.0)
			{
				message = String.format("This item is worth %.1f seconds of minimum wage work.", seconds);
			}
			else if (seconds >= 0.01)
			{
				message = String.format("This item is worth %.2f seconds of minimum wage work.", seconds);
			}
			else
			{
				// For extremely small values
				message = "This item is worth <0.01 seconds of minimum wage work.";
			}
		}

		// Send chat message
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.enabled())
		{
			return;
		}

		// Update bond price if needed
		if (bondPrice <= 0 || System.currentTimeMillis() - lastBondPriceUpdate > BOND_PRICE_CACHE_MS)
		{
			updateBondPrice();
		}

		if (bondPrice <= 0)
		{
			return;
		}

		// Check if we're hovering over an item
		int itemId = event.getItemId();
		
		if (itemId == -1)
		{
			// Reset when not hovering over an item
			lastItemId = -1;
			tooltipAdded = false;
			return;
		}

		// Only add tooltip once per item (avoid duplicates from multiple menu entries)
		if (itemId == lastItemId && tooltipAdded)
		{
			return;
		}

		lastItemId = itemId;
		tooltipAdded = true;

		// Get item price from ItemManager
		int itemPrice = itemManager.getItemPrice(itemId);
		
		if (itemPrice <= 0)
		{
			return;
		}

		// Calculate real world value
		double realWorldValue = calculateRealWorldValue(itemPrice);

		if (realWorldValue < config.minimumValue())
		{
			return;
		}

		// Create tooltip text
		String tooltipText = buildTooltipText(itemPrice, realWorldValue);

		// Add tooltip
		tooltipManager.add(new Tooltip(tooltipText));
	}

	private void updateBondPrice()
	{
		bondPrice = itemManager.getItemPrice(BOND_ITEM_ID);
		lastBondPriceUpdate = System.currentTimeMillis();
		
		if (bondPrice > 0)
		{
			log.debug("Bond price updated: {} gp", bondPrice);
		}
		else
		{
			log.warn("Failed to fetch bond price");
		}
	}

	private double calculateRealWorldValue(int gpValue)
	{
		if (bondPrice <= 0)
		{
			return 0;
		}
		
		// Get bond price in selected currency
		double bondRealPrice;
		switch (config.currency())
		{
			case GBP:
				bondRealPrice = BOND_GBP_PRICE;
				break;
			case EUR:
				bondRealPrice = BOND_EUR_PRICE;
				break;
			case CAD:
				bondRealPrice = BOND_CAD_PRICE;
				break;
			case AUD:
				bondRealPrice = BOND_AUD_PRICE;
				break;
			case NOK:
				bondRealPrice = BOND_NOK_PRICE;
				break;
			case SEK:
				bondRealPrice = BOND_SEK_PRICE;
				break;
			case BRL:
				bondRealPrice = BOND_BRL_PRICE;
				break;
			case PLN:
				bondRealPrice = BOND_PLN_PRICE;
				break;
			case SGD:
				bondRealPrice = BOND_SGD_PRICE;
				break;
			case DKK:
				bondRealPrice = BOND_DKK_PRICE;
				break;
			case USD:
			default:
				bondRealPrice = BOND_USD_PRICE;
				break;
		}
		
		// Real world value per GP = Bond real price / Bond GP price
		double realWorldPerGp = bondRealPrice / bondPrice;
		return gpValue * realWorldPerGp;
	}

	private double getMinimumWage()
	{
		switch (config.currency())
		{
			case GBP:
				return MIN_WAGE_GBP;
			case EUR:
				return MIN_WAGE_EUR;
			case CAD:
				return MIN_WAGE_CAD;
			case AUD:
				return MIN_WAGE_AUD;
			case NOK:
				return MIN_WAGE_NOK;
			case SEK:
				return MIN_WAGE_SEK;
			case BRL:
				return MIN_WAGE_BRL;
			case PLN:
				return MIN_WAGE_PLN;
			case SGD:
				return MIN_WAGE_SGD;
			case DKK:
				return MIN_WAGE_DKK;
			case USD:
			default:
				return MIN_WAGE_USD;
		}
	}

	private String formatCurrency(double value)
	{
		String symbol;
		switch (config.currency())
		{
			case GBP:
				symbol = "£";
				break;
			case EUR:
				symbol = "€";
				break;
			case CAD:
				symbol = "C$";
				break;
			case AUD:
				symbol = "A$";
				break;
			case NOK:
				symbol = "NOK";
				break;
			case SEK:
				symbol = "kr";
				break;
			case BRL:
				symbol = "R$";
				break;
			case PLN:
				symbol = "zł";
				break;
			case SGD:
				symbol = "S$";
				break;
			case DKK:
				symbol = "DKK";
				break;
			case USD:
			default:
				symbol = "$";
				break;
		}
		
		String formattedValue;
		if (value >= 0.01)
		{
			// For values >= 0.01, show 2 decimal places
			formattedValue = String.format("%s%,.2f", symbol, value);
		}
		else if (value >= 0.0001)
		{
			// For values >= 0.0001, show 4 decimal places
			formattedValue = String.format("%s%.4f", symbol, value);
		}
		else if (value >= 0.000001)
		{
			// For values >= 0.000001, show 6 decimal places
			formattedValue = String.format("%s%.6f", symbol, value);
		}
		else
		{
			// For very small values, use scientific notation
			formattedValue = String.format("%s%.2e", symbol, value);
		}
		
		// Display in bright green color
		return String.format("<col=1EFF00>%s</col>", formattedValue);
	}

	private String buildTooltipText(int gpValue, double realWorldValue)
	{
		StringBuilder sb = new StringBuilder();
		
		if (config.showGPValue())
		{
			String gpFormatted = QuantityFormatter.quantityToStackSize(gpValue);
			
			// Color green if value is above 10M
			if (gpValue >= 10_000_000)
			{
				sb.append("GE Value: ")
				  .append(String.format("<col=1EFF00>%s</col>", gpFormatted))
				  .append(" gp");
			}
			else
			{
				sb.append("GE Value: ")
				  .append(gpFormatted)
				  .append(" gp");
			}
			
			sb.append("</br>");
		}
		
		sb.append("Real Value: ")
		  .append(formatCurrency(realWorldValue));
		
		if (config.showBondPrice())
		{
			String bondFormatted = QuantityFormatter.quantityToStackSize(bondPrice);
			
			sb.append("</br>")
			  .append("(Bond: ");
			
			// Color green if bond price is above 10M
			if (bondPrice >= 10_000_000)
			{
				sb.append(String.format("<col=1EFF00>%s</col>", bondFormatted));
			}
			else
			{
				sb.append(bondFormatted);
			}
			
			sb.append(" gp)");
		}
		
		return sb.toString();
	}

	@Provides
	RealWorldValueConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RealWorldValueConfig.class);
	}
}
