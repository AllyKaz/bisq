/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.desktop.main.market.trades;

import bisq.desktop.main.market.trades.charts.CandleData;
import bisq.desktop.util.DisplayUtils;

import bisq.core.locale.CurrencyUtil;
import bisq.core.monetary.Altcoin;
import bisq.core.trade.statistics.TradeStatistics3;

import bisq.common.util.MathUtils;

import org.bitcoinj.core.Coin;

import com.google.common.annotations.VisibleForTesting;

import javafx.scene.chart.XYChart;

import javafx.util.Pair;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import lombok.Getter;

import static bisq.desktop.main.market.trades.TradesChartsViewModel.MAX_TICKS;

public class ChartCalculations {
    @VisibleForTesting
    static final ZoneId ZONE_ID = ZoneId.systemDefault();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Async
    ///////////////////////////////////////////////////////////////////////////////////////////

    static CompletableFuture<Map<TradesChartsViewModel.TickUnit, Map<Long, Long>>> getUsdAveragePriceMapsPerTickUnit(Set<TradeStatistics3> tradeStatisticsSet) {
        return CompletableFuture.supplyAsync(() -> {
            Map<TradesChartsViewModel.TickUnit, Map<Long, Long>> usdAveragePriceMapsPerTickUnit = new HashMap<>();
            Map<TradesChartsViewModel.TickUnit, Map<Long, List<TradeStatistics3>>> dateMapsPerTickUnit = new HashMap<>();
            for (TradesChartsViewModel.TickUnit tick : TradesChartsViewModel.TickUnit.values()) {
                dateMapsPerTickUnit.put(tick, new HashMap<>());
            }

            TradesChartsViewModel.TickUnit[] tickUnits = TradesChartsViewModel.TickUnit.values();
            tradeStatisticsSet.stream()
                    .filter(e -> e.getCurrency().equals("USD"))
                    .forEach(tradeStatistics -> {
                        for (TradesChartsViewModel.TickUnit tickUnit : tickUnits) {
                            long time = roundToTick(tradeStatistics.getLocalDateTime(), tickUnit).getTime();
                            Map<Long, List<TradeStatistics3>> map = dateMapsPerTickUnit.get(tickUnit);
                            map.computeIfAbsent(time, t -> new ArrayList<>()).add(tradeStatistics);
                        }
                    });

            dateMapsPerTickUnit.forEach((tickUnit, map) -> {
                HashMap<Long, Long> priceMap = new HashMap<>();
                map.forEach((date, tradeStatisticsList) -> priceMap.put(date, getAveragePrice(tradeStatisticsList)));
                usdAveragePriceMapsPerTickUnit.put(tickUnit, priceMap);
            });
            return usdAveragePriceMapsPerTickUnit;
        });
    }

    static CompletableFuture<List<TradeStatistics3>> getTradeStatisticsForCurrency(Set<TradeStatistics3> tradeStatisticsSet,
                                                                                   String currencyCode,
                                                                                   boolean showAllTradeCurrencies) {
        return CompletableFuture.supplyAsync(() -> tradeStatisticsSet.stream()
                .filter(e -> showAllTradeCurrencies || e.getCurrency().equals(currencyCode))
                .collect(Collectors.toList()));
    }

    static CompletableFuture<UpdateChartResult> getUpdateChartResult(List<TradeStatistics3> tradeStatisticsByCurrency,
                                                                     TradesChartsViewModel.TickUnit tickUnit,
                                                                     Map<TradesChartsViewModel.TickUnit, Map<Long, Long>> usdAveragePriceMapsPerTickUnit,
                                                                     String currencyCode) {
        return CompletableFuture.supplyAsync(() -> {
            // Generate date range and create sets for all ticks
            List<Pair<Date, Set<TradeStatistics3>>> itemsPerInterval = getItemsPerInterval(tradeStatisticsByCurrency, tickUnit);

            Map<Long, Long> usdAveragePriceMap = usdAveragePriceMapsPerTickUnit.get(tickUnit);
            AtomicLong averageUsdPrice = new AtomicLong(0);

            // create CandleData for defined time interval
            List<CandleData> candleDataList = IntStream.range(0, itemsPerInterval.size())
                    .filter(i -> !itemsPerInterval.get(i).getValue().isEmpty())
                    .mapToObj(i -> {
                        Pair<Date, Set<TradeStatistics3>> pair = itemsPerInterval.get(i);
                        long tickStartDate = pair.getKey().getTime();
                        // If we don't have a price we take the previous one
                        if (usdAveragePriceMap.containsKey(tickStartDate)) {
                            averageUsdPrice.set(usdAveragePriceMap.get(tickStartDate));
                        }
                        return getCandleData(i, pair.getValue(), averageUsdPrice.get(), tickUnit, currencyCode, itemsPerInterval);
                    })
                    .sorted(Comparator.comparingLong(o -> o.tick))
                    .collect(Collectors.toList());

            List<XYChart.Data<Number, Number>> priceItems = candleDataList.stream()
                    .map(e -> new XYChart.Data<Number, Number>(e.tick, e.open, e))
                    .collect(Collectors.toList());

            List<XYChart.Data<Number, Number>> volumeItems = candleDataList.stream()
                    .map(candleData -> new XYChart.Data<Number, Number>(candleData.tick, candleData.accumulatedAmount, candleData))
                    .collect(Collectors.toList());

            List<XYChart.Data<Number, Number>> volumeInUsdItems = candleDataList.stream()
                    .map(candleData -> new XYChart.Data<Number, Number>(candleData.tick, candleData.volumeInUsd, candleData))
                    .collect(Collectors.toList());

            return new UpdateChartResult(itemsPerInterval, priceItems, volumeItems, volumeInUsdItems);
        });
    }

    @Getter
    static class UpdateChartResult {
        private final List<Pair<Date, Set<TradeStatistics3>>> itemsPerInterval;
        private final List<XYChart.Data<Number, Number>> priceItems;
        private final List<XYChart.Data<Number, Number>> volumeItems;
        private final List<XYChart.Data<Number, Number>> volumeInUsdItems;

        public UpdateChartResult(List<Pair<Date, Set<TradeStatistics3>>> itemsPerInterval,
                                 List<XYChart.Data<Number, Number>> priceItems,
                                 List<XYChart.Data<Number, Number>> volumeItems,
                                 List<XYChart.Data<Number, Number>> volumeInUsdItems) {

            this.itemsPerInterval = itemsPerInterval;
            this.priceItems = priceItems;
            this.volumeItems = volumeItems;
            this.volumeInUsdItems = volumeInUsdItems;
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    static List<Pair<Date, Set<TradeStatistics3>>> getItemsPerInterval(List<TradeStatistics3> tradeStatisticsByCurrency,
                                                                       TradesChartsViewModel.TickUnit tickUnit) {
        // Generate date range and create sets for all ticks
        List<Pair<Date, Set<TradeStatistics3>>> itemsPerInterval = new ArrayList<>(Collections.nCopies(MAX_TICKS + 2, null));
        Date time = new Date();
        for (int i = MAX_TICKS + 1; i >= 0; --i) {
            Pair<Date, Set<TradeStatistics3>> pair = new Pair<>((Date) time.clone(), new HashSet<>());
            itemsPerInterval.set(i, pair);
            // We adjust the time for the next iteration
            time.setTime(time.getTime() - 1);
            time = roundToTick(time, tickUnit);
        }

        // Get all entries for the defined time interval
        int i = MAX_TICKS;
        for (TradeStatistics3 tradeStatistics : tradeStatisticsByCurrency) {
            // Start from the last used tick index - move forwards if necessary
            for (; i < MAX_TICKS; i++) {
                Pair<Date, Set<TradeStatistics3>> pair = itemsPerInterval.get(i + 1);
                if (!tradeStatistics.getDate().after(pair.getKey())) {
                    break;
                }
            }
            // Scan backwards until the correct tick is reached
            for (; i > 0; --i) {
                Pair<Date, Set<TradeStatistics3>> pair = itemsPerInterval.get(i);
                if (tradeStatistics.getDate().after(pair.getKey())) {
                    pair.getValue().add(tradeStatistics);
                    break;
                }
            }
        }
        return itemsPerInterval;
    }


    private static Date roundToTick(LocalDateTime localDate, TradesChartsViewModel.TickUnit tickUnit) {
        switch (tickUnit) {
            case YEAR:
                return Date.from(localDate.withMonth(1).withDayOfYear(1).withHour(0).withMinute(0).withSecond(0).withNano(0).atZone(ZONE_ID).toInstant());
            case MONTH:
                return Date.from(localDate.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0).atZone(ZONE_ID).toInstant());
            case WEEK:
                int dayOfWeek = localDate.getDayOfWeek().getValue();
                LocalDateTime firstDayOfWeek = ChronoUnit.DAYS.addTo(localDate, 1 - dayOfWeek);
                return Date.from(firstDayOfWeek.withHour(0).withMinute(0).withSecond(0).withNano(0).atZone(ZONE_ID).toInstant());
            case DAY:
                return Date.from(localDate.withHour(0).withMinute(0).withSecond(0).withNano(0).atZone(ZONE_ID).toInstant());
            case HOUR:
                return Date.from(localDate.withMinute(0).withSecond(0).withNano(0).atZone(ZONE_ID).toInstant());
            case MINUTE_10:
                return Date.from(localDate.withMinute(localDate.getMinute() - localDate.getMinute() % 10).withSecond(0).withNano(0).atZone(ZONE_ID).toInstant());
            default:
                return Date.from(localDate.atZone(ZONE_ID).toInstant());
        }
    }

    @VisibleForTesting
    static Date roundToTick(Date time, TradesChartsViewModel.TickUnit tickUnit) {
        return roundToTick(time.toInstant().atZone(ChartCalculations.ZONE_ID).toLocalDateTime(), tickUnit);
    }

    private static long getAveragePrice(List<TradeStatistics3> tradeStatisticsList) {
        long accumulatedAmount = 0;
        long accumulatedVolume = 0;
        for (TradeStatistics3 tradeStatistics : tradeStatisticsList) {
            accumulatedAmount += tradeStatistics.getAmount();
            accumulatedVolume += tradeStatistics.getTradeVolume().getValue();
        }

        double accumulatedVolumeAsDouble = MathUtils.scaleUpByPowerOf10((double) accumulatedVolume, Coin.SMALLEST_UNIT_EXPONENT);
        return MathUtils.roundDoubleToLong(accumulatedVolumeAsDouble / (double) accumulatedAmount);
    }

    @VisibleForTesting
    static CandleData getCandleData(int tickIndex, Set<TradeStatistics3> set,
                                    long averageUsdPrice,
                                    TradesChartsViewModel.TickUnit tickUnit,
                                    String currencyCode,
                                    List<Pair<Date, Set<TradeStatistics3>>> itemsPerInterval) {
        long open = 0;
        long close = 0;
        long high = 0;
        long low = 0;
        long accumulatedVolume = 0;
        long accumulatedAmount = 0;
        long numTrades = set.size();
        List<Long> tradePrices = new ArrayList<>();
        for (TradeStatistics3 item : set) {
            long tradePriceAsLong = item.getTradePrice().getValue();
            // Previously a check was done which inverted the low and high for cryptocurrencies.
            low = (low != 0) ? Math.min(low, tradePriceAsLong) : tradePriceAsLong;
            high = (high != 0) ? Math.max(high, tradePriceAsLong) : tradePriceAsLong;

            accumulatedVolume += item.getTradeVolume().getValue();
            accumulatedAmount += item.getTradeAmount().getValue();
            tradePrices.add(tradePriceAsLong);
        }
        Collections.sort(tradePrices);

        List<TradeStatistics3> list = new ArrayList<>(set);
        list.sort(Comparator.comparingLong(TradeStatistics3::getDateAsLong));
        if (list.size() > 0) {
            open = list.get(0).getTradePrice().getValue();
            close = list.get(list.size() - 1).getTradePrice().getValue();
        }

        long averagePrice;
        Long[] prices = new Long[tradePrices.size()];
        tradePrices.toArray(prices);
        long medianPrice = MathUtils.getMedian(prices);
        boolean isBullish;
        if (CurrencyUtil.isCryptoCurrency(currencyCode)) {
            isBullish = close < open;
            double accumulatedAmountAsDouble = MathUtils.scaleUpByPowerOf10((double) accumulatedAmount, Altcoin.SMALLEST_UNIT_EXPONENT);
            averagePrice = MathUtils.roundDoubleToLong(accumulatedAmountAsDouble / (double) accumulatedVolume);
        } else {
            isBullish = close > open;
            double accumulatedVolumeAsDouble = MathUtils.scaleUpByPowerOf10((double) accumulatedVolume, Coin.SMALLEST_UNIT_EXPONENT);
            averagePrice = MathUtils.roundDoubleToLong(accumulatedVolumeAsDouble / (double) accumulatedAmount);
        }

        Date dateFrom = new Date(getTimeFromTickIndex(tickIndex, itemsPerInterval));
        Date dateTo = new Date(getTimeFromTickIndex(tickIndex + 1, itemsPerInterval));
        String dateString = tickUnit.ordinal() > TradesChartsViewModel.TickUnit.DAY.ordinal() ?
                DisplayUtils.formatDateTimeSpan(dateFrom, dateTo) :
                DisplayUtils.formatDate(dateFrom) + " - " + DisplayUtils.formatDate(dateTo);

        // We do not need precision, so we scale down before multiplication otherwise we could get an overflow.
        averageUsdPrice = (long) MathUtils.scaleDownByPowerOf10((double) averageUsdPrice, 4);
        long volumeInUsd = averageUsdPrice * (long) MathUtils.scaleDownByPowerOf10((double) accumulatedAmount, 4);
        // We store USD value without decimals as its only total volume, no precision is needed.
        volumeInUsd = (long) MathUtils.scaleDownByPowerOf10((double) volumeInUsd, 4);
        return new CandleData(tickIndex, open, close, high, low, averagePrice, medianPrice, accumulatedAmount, accumulatedVolume,
                numTrades, isBullish, dateString, volumeInUsd);
    }

    static long getTimeFromTickIndex(int tickIndex, List<Pair<Date, Set<TradeStatistics3>>> itemsPerInterval) {
        if (tickIndex < 0 || tickIndex >= itemsPerInterval.size()) {
            return 0;
        }
        return itemsPerInterval.get(tickIndex).getKey().getTime();
    }
}
