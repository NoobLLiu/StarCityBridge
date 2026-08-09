package com.starcity.bridge.module.market;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.web.WebMarketManager;
import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.module.BridgeModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 市场插件（StockExchange）对接模块。
 * <p>把网站后端的 market 请求转发给 StockExchange 的 WebMarketManager 执行；
 * 写操作由 WebMarketManager 内部强制在主线程串行执行，保证与游戏内操作互斥。</p>
 */
public class MarketModule implements BridgeModule {

    private final StarCityBridge plugin;

    public MarketModule(StarCityBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "market";
    }

    private WebMarketManager market() {
        Plugin exchange = Bukkit.getPluginManager().getPlugin("StockExchange");
        if (exchange == null || !exchange.isEnabled()) {
            return null;
        }
        return ((StockExchangePlugin) exchange).getWebMarketManager();
    }

    @Override
    public JsonObject handleRequest(String action, JsonObject payload) {
        WebMarketManager market = market();
        if (market == null) {
            return result(false, "市场插件未启用或未安装", null);
        }
        Map<String, Object> r;
        try {
            switch (action) {
                case "list_items":
                    r = market.listItems();
                    break;
                case "list_items_page":
                    r = market.listItems(
                        strField(payload, "player_uuid"),
                        boolField(payload, "buy_page"),
                        strField(payload, "query"),
                        intField(payload, "page"),
                        intField(payload, "page_size")
                    );
                    break;
                case "item_detail":
                    r = market.itemDetail(intField(payload, "item_id"));
                    break;
                case "item_detail_full":
                    r = market.itemDetail(
                        strField(payload, "player_uuid"),
                        intField(payload, "item_id"),
                        boolField(payload, "buy_page"),
                        intField(payload, "page"),
                        intField(payload, "page_size")
                    );
                    break;
                case "order_book":
                    r = market.orderBook(intField(payload, "item_id"));
                    break;
                case "my_history":
                    r = market.myHistory(strField(payload, "player_uuid"), intField(payload, "page"), intField(payload, "page_size"));
                    break;
                case "my_orders":
                    r = market.myOrders(strField(payload, "player_uuid"));
                    break;
                case "my_trades":
                    r = market.myTrades(strField(payload, "player_uuid"), intField(payload, "page"), intField(payload, "size"));
                    break;
                case "my_warehouse":
                    r = market.myWarehouse(strField(payload, "player_uuid"));
                    break;
                case "market_info":
                    r = market.marketInfo();
                    break;
                case "announcements":
                    r = market.announcements(intField(payload, "page"), intField(payload, "page_size"));
                    break;
                case "catalog_search":
                    r = market.catalogSearch(strField(payload, "query"));
                    break;
                case "supply_plan":
                    r = market.supplyPlan(strField(payload, "player_uuid"), intField(payload, "item_id"));
                    break;
                case "place_buy":
                    r = market.placeBuy(strField(payload, "player_uuid"), intField(payload, "item_id"),
                            decField(payload, "price"), intField(payload, "quantity"));
                    break;
                case "place_sell":
                    r = market.placeSell(strField(payload, "player_uuid"), intField(payload, "item_id"),
                            decField(payload, "price"), intField(payload, "quantity"));
                    break;
                case "place_sell_item":
                    r = market.placeSell(strField(payload, "player_uuid"), intField(payload, "item_id"),
                            decField(payload, "price"), intField(payload, "quantity"), strField(payload, "item_base64"));
                    break;
                case "cancel":
                    r = market.cancel(strField(payload, "player_uuid"), intField(payload, "order_id"), boolField(payload, "admin"));
                    break;
                case "withdraw_order":
                    r = market.withdrawOrderQuantity(
                        strField(payload, "player_uuid"),
                        intField(payload, "order_id"),
                        intField(payload, "quantity"),
                        boolField(payload, "admin")
                    );
                    break;
                case "market_buy":
                    r = market.marketBuy(strField(payload, "player_uuid"), intField(payload, "item_id"), intField(payload, "quantity"));
                    break;
                case "market_sell":
                    r = market.marketSell(strField(payload, "player_uuid"), intField(payload, "item_id"), intField(payload, "quantity"));
                    break;
                case "direct_buy":
                    r = market.directBuy(strField(payload, "player_uuid"), intField(payload, "sell_order_id"), intField(payload, "quantity"));
                    break;
                case "direct_sell":
                    r = market.directSell(strField(payload, "player_uuid"), intField(payload, "buy_order_id"), intField(payload, "quantity"));
                    break;
                case "quick_sell":
                    r = market.quickSell(strField(payload, "player_uuid"), intField(payload, "item_id"));
                    break;
                case "supply_all":
                    r = market.supplyAll(strField(payload, "player_uuid"), intField(payload, "item_id"));
                    break;
                case "register_item":
                    r = market.registerCatalogItem(strField(payload, "player_uuid"), strField(payload, "item_base64"), boolField(payload, "admin"));
                    break;
                case "exchange_d2m":
                    r = market.exchangeDiamondForMoney(strField(payload, "player_uuid"));
                    break;
                case "exchange_m2d":
                    r = market.exchangeMoneyForDiamond(strField(payload, "player_uuid"));
                    break;
                case "deposit_money":
                    r = market.depositMoney(strField(payload, "player_uuid"), decField(payload, "amount"));
                    break;
                case "withdraw_money":
                    r = market.withdrawMoney(strField(payload, "player_uuid"), decField(payload, "amount"));
                    break;
                case "warehouse_deposit_hand":
                    r = market.depositHandItem(strField(payload, "player_uuid"), intField(payload, "quantity"));
                    break;
                case "warehouse_withdraw_all":
                    r = market.warehouseWithdrawAll(strField(payload, "player_uuid"));
                    break;
                case "warehouse_withdraw_money":
                    r = market.warehouseWithdrawMoney(strField(payload, "player_uuid"));
                    break;
                case "warehouse_withdraw_item":
                    r = market.warehouseWithdrawItem(strField(payload, "player_uuid"), strField(payload, "item_base64"));
                    break;
                case "market_stats":
                    r = market.getMarketStats(intField(payload, "days"));
                    break;
                case "admin_suspend":
                    r = market.adminSuspend(intField(payload, "item_id"), boolField(payload, "suspend"));
                    break;
                case "admin_set_tax":
                    r = market.adminSetTax(decField(payload, "percent"));
                    break;
                case "admin_announcement":
                    r = market.adminAnnouncement(strField(payload, "action"), intField(payload, "id"), strField(payload, "content"));
                    break;
                case "admin_reload":
                    r = market.adminReload();
                    break;
                case "admin_reconnect":
                    r = market.adminReconnectDb();
                    break;
                default:
                    return null;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[market] 执行失败: " + action + " -> " + e.getMessage());
            return result(false, "市场操作失败: " + e.getMessage(), null);
        }
        boolean ok = Boolean.TRUE.equals(r.get("ok"));
        Object data = r.get("data");
        Object message = r.get("message");
        return result(ok, message == null ? "" : String.valueOf(message), data);
    }

    // ---- JSON 转换工具 ----

    private int intField(JsonObject payload, String key) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsInt() : 0;
    }

    private String strField(JsonObject payload, String key) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsString() : "";
    }

    private boolean boolField(JsonObject payload, String key) {
        return payload.has(key) && !payload.get(key).isJsonNull() && payload.get(key).getAsBoolean();
    }

    private BigDecimal decField(JsonObject payload, String key) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsBigDecimal() : BigDecimal.ZERO;
    }

    private JsonObject result(boolean ok, String message, Object data) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("message", message);
        if (data != null) {
            out.add("data", plugin.gson().toJsonTree(data));
        } else {
            out.add("data", new JsonObject());
        }
        return out;
    }
}
