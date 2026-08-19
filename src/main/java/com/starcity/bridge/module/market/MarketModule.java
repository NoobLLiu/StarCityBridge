package com.starcity.bridge.module.market;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.web.WebMarketManager;
import com.google.gson.JsonObject;
import com.starcity.bridge.StarCityBridge;
import com.starcity.bridge.module.BridgeModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
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
        Object r;
        try {
            r = switch (action) {
                case "list_items":
                    yield market.listItems();
                case "list_items_page":
                    yield market.listItems(
                        strField(payload, "player_uuid"),
                        boolField(payload, "buy_page"),
                        strField(payload, "query"),
                        intField(payload, "page"),
                        intField(payload, "page_size")
                    );
                case "item_detail":
                    yield market.itemDetail(intField(payload, "item_id"));
                case "item_detail_full":
                    yield market.itemDetail(
                        strField(payload, "player_uuid"),
                        intField(payload, "item_id"),
                        boolField(payload, "buy_page"),
                        intField(payload, "page"),
                        intField(payload, "page_size")
                    );
                case "order_book":
                    yield market.orderBook(intField(payload, "item_id"));
                case "my_history":
                    yield market.myHistory(strField(payload, "player_uuid"), intField(payload, "page"), intField(payload, "page_size"));
                case "my_orders":
                    yield market.myOrders(strField(payload, "player_uuid"));
                case "my_trades":
                    yield market.myTrades(strField(payload, "player_uuid"), intField(payload, "page"), intField(payload, "size"));
                case "my_warehouse":
                    yield market.myWarehouse(strField(payload, "player_uuid"));
                case "my_balance":
                    yield market.myBalance(strField(payload, "player_uuid"));
                case "market_info":
                    yield market.marketInfo();
                case "announcements":
                    yield market.announcements(intField(payload, "page"), intField(payload, "page_size"));
                case "catalog_search":
                    yield market.catalogSearch(strField(payload, "query"));
                case "supply_plan":
                    yield market.supplyPlan(strField(payload, "player_uuid"), intField(payload, "item_id"));
                case "place_buy":
                    yield market.placeBuy(strField(payload, "player_uuid"), intField(payload, "item_id"),
                            decField(payload, "price"), intField(payload, "quantity"));
                case "place_sell":
                    yield market.placeSell(strField(payload, "player_uuid"), intField(payload, "item_id"),
                            decField(payload, "price"), intField(payload, "quantity"));
                case "place_sell_item":
                    yield market.placeSell(strField(payload, "player_uuid"), intField(payload, "item_id"),
                            decField(payload, "price"), intField(payload, "quantity"), strField(payload, "item_base64"));
                case "cancel":
                    yield market.cancel(strField(payload, "player_uuid"), intField(payload, "order_id"), boolField(payload, "admin"));
                case "withdraw_order":
                    yield market.withdrawOrderQuantity(
                        strField(payload, "player_uuid"),
                        intField(payload, "order_id"),
                        intField(payload, "quantity"),
                        boolField(payload, "admin")
                    );
                case "market_buy":
                    yield market.marketBuy(strField(payload, "player_uuid"), intField(payload, "item_id"), intField(payload, "quantity"));
                case "market_sell":
                    yield market.marketSell(strField(payload, "player_uuid"), intField(payload, "item_id"), intField(payload, "quantity"));
                case "direct_buy":
                    yield market.directBuy(strField(payload, "player_uuid"), intField(payload, "sell_order_id"), intField(payload, "quantity"));
                case "direct_sell":
                    yield market.directSell(strField(payload, "player_uuid"), intField(payload, "buy_order_id"), intField(payload, "quantity"));
                case "quick_sell":
                    yield market.quickSell(strField(payload, "player_uuid"), intField(payload, "item_id"));
                case "supply_all":
                    yield market.supplyAll(strField(payload, "player_uuid"), intField(payload, "item_id"));
                case "register_item":
                    yield market.registerCatalogItem(strField(payload, "player_uuid"), strField(payload, "item_base64"), boolField(payload, "admin"));
                case "exchange_d2m":
                    yield market.exchangeDiamondForMoney(strField(payload, "player_uuid"));
                case "exchange_m2d":
                    yield market.exchangeMoneyForDiamond(strField(payload, "player_uuid"));
                case "deposit_money":
                    yield market.depositMoney(strField(payload, "player_uuid"), decField(payload, "amount"));
                case "withdraw_money":
                    yield market.withdrawMoney(strField(payload, "player_uuid"), decField(payload, "amount"));
                case "warehouse_deposit_hand":
                    yield market.depositHandItem(strField(payload, "player_uuid"), intField(payload, "quantity"));
                case "warehouse_withdraw_all":
                    yield market.warehouseWithdrawAll(strField(payload, "player_uuid"));
                case "warehouse_withdraw_money":
                    yield market.warehouseWithdrawMoney(strField(payload, "player_uuid"));
                case "warehouse_withdraw_item":
                    yield market.warehouseWithdrawItem(strField(payload, "player_uuid"), strField(payload, "item_base64"));
                case "market_stats":
                    yield market.getMarketStats(intField(payload, "days"));
                case "admin_suspend":
                    yield market.adminSuspend(boolField(payload, "admin"), intField(payload, "item_id"), boolField(payload, "suspend"));
                case "admin_set_tax":
                    yield market.adminSetTax(boolField(payload, "admin"), decField(payload, "percent"));
                case "admin_announcement":
                    yield market.adminAnnouncement(boolField(payload, "admin"), strField(payload, "action"), intField(payload, "id"), strField(payload, "content"));
                case "admin_reload":
                    yield market.adminReload(boolField(payload, "admin"));
                case "admin_reconnect":
                    yield market.adminReconnectDb(boolField(payload, "admin"));
                default:
                    yield null;
            };
        } catch (Exception e) {
            plugin.getLogger().warning("[market] 执行失败: " + action + " -> " + e.getMessage());
            return result(false, "市场操作失败: " + e.getMessage(), null);
        }
        if (r == null) return null;
        if (r instanceof List) {
            return result(true, "", r);
        }
        Map<String, Object> m = (Map<String, Object>) r;
        boolean ok = Boolean.TRUE.equals(m.get("ok"));
        Object data = m.get("data");
        Object message = m.get("message");
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
