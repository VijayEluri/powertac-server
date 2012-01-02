/*
* Copyright 2011 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an
* "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
* either express or implied. See the License for the specific language
* governing permissions and limitations under the License.
*/

package org.powertac.factoredcustomer;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.*;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.powertac.common.TariffSubscription;
import org.powertac.common.TimeService;
import org.powertac.common.Timeslot;
import org.powertac.common.WeatherReport;
import org.powertac.common.repo.WeatherReportRepo;
import org.powertac.common.spring.SpringApplicationContext;
import org.powertac.factoredcustomer.CapacityProfile.BaseCapacityType;
import org.powertac.factoredcustomer.CapacityProfile.CapacityType;
import org.powertac.factoredcustomer.CapacityProfile.InfluenceKind;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Key class responsible for drawing from a base capacity and ajusting that 
 * capacity in response to various static and dynamic factors for each timeslot.
 * 
 * @author Prashant Reddy
 */
final class DefaultCapacityManager implements CapacityManager
{
    private static Logger log = Logger.getLogger(DefaultCapacityManager.class.getName());

    @Autowired
    private TimeService timeService;
    
    @Autowired
    private WeatherReportRepo weatherReportRepo;
    
    private final TimeseriesGenerator tsGenerator;
    
    private final CustomerProfile customerProfile;
    private final CapacityProfile capacityProfile;
    
    private final List<Double> baseCapacities = new ArrayList<Double>();
    private final List<Double> adjCapacities = new ArrayList<Double>();
    
    
    DefaultCapacityManager(CustomerProfile customer, CapacityBundle bundle, Element xml) 
    {
        customerProfile = customer;
        capacityProfile = new CapacityProfile(xml, bundle);
        
        timeService = (TimeService) SpringApplicationContext.getBean("timeService");
        weatherReportRepo = (WeatherReportRepo) SpringApplicationContext.getBean("weatherReportRepo");
        
        if (capacityProfile.baseCapacityType == BaseCapacityType.TIMESERIES) {
            tsGenerator = new TimeseriesGenerator(capacityProfile.baseTimeseriesProfile);
        } 
        else tsGenerator = null; 
   
        baseCapacities.add(0.0);  // set capacity at timeslot 0 = 0.0
        adjCapacities.add(0.0);   
    }
    
    private double getPopulationRatio(int customerCount, int population) 
    {
        return ((double) customerCount) / ((double) population);
    }
    
    /** @Override @code{CapacityManager} **/
    public double getBaseCapacity(Timeslot timeslot) 
    {
        int t = timeslot.getSerialNumber();
        if (t < baseCapacities.size()) {
            return baseCapacities.get(t);
        } else {
            return drawBaseCapacitySample(timeslot);
        }
    }
    
    public double drawBaseCapacitySample(Timeslot timeslot) 
    {    
        int t = timeslot.getSerialNumber();
        double baseCapacity = 0.0;
        switch (capacityProfile.baseCapacityType) {
        case POPULATION:
            baseCapacity = capacityProfile.basePopulationCapacity.drawSample();
            break;
        case INDIVIDUAL:
            for (int i=0; i < customerProfile.customerInfo.getPopulation(); ++i) {
                double draw = capacityProfile.baseIndividualCapacity.drawSample();
                baseCapacity += draw;
            }
            break;
        case TIMESERIES:
            baseCapacity = getBaseCapacityFromTimeseries(timeslot);
            break;            
        default: throw new Error(getName() + ": Unexpected base capacity type: " + capacityProfile.baseCapacityType);
        }
        if (t > 1) baseCapacity = (baseCapacity + baseCapacities.get(t-1)) / 2;  // smoothing
        baseCapacity = truncateTo2Decimals(baseCapacity);
        baseCapacities.add(baseCapacity);
        return baseCapacity;
    }
	
    private double getBaseCapacityFromTimeseries(Timeslot timeslot)
    {
        try {
            return tsGenerator.generateNext(timeslot);
        } catch (ArrayIndexOutOfBoundsException e) {
            log.error(getName() + ": Tried to get base capacity from time series at index beyond maximum!");
            throw e;
        }
    }
        
    /** Override @code{CapacityManager} **/
    public double useCapacity(Timeslot timeslot, TariffSubscription subscription)
    {
        double baseCapacity = getBaseCapacity(timeslot);
        if (Double.isNaN(baseCapacity)) throw new Error("Base capacity is NaN!");
        logCapacityDetails(getName() + ": Base capacity for timeslot " + timeslot.getSerialNumber() + " = " + baseCapacity);        

        double adjustedCapacity = baseCapacity;
        adjustedCapacity = adjustCapacityForPopulationRatio(adjustedCapacity, subscription);
        adjustedCapacity = adjustCapacityForPeriodicSkew(adjustedCapacity);
        adjustedCapacity = adjustCapacityForWeather(timeslot, adjustedCapacity);                
        adjustedCapacity = adjustCapacityForTariffRates(timeslot, subscription, adjustedCapacity);
        if (Double.isNaN(adjustedCapacity)) throw new Error("Adjusted capacity is NaN for base capacity = " + baseCapacity);
        
        adjustedCapacity = truncateTo2Decimals(adjustedCapacity);
        adjCapacities.add(adjustedCapacity);        
        log.info(getName() + ": Adjusted capacity for tariff " + subscription.getTariff().getId() + " = " + adjustedCapacity);        
        return adjustedCapacity;
    }

    private double adjustCapacityForPopulationRatio(double capacity, TariffSubscription subscription)
    {
        double popRatio = getPopulationRatio(subscription.getCustomersCommitted(), customerProfile.customerInfo.getPopulation());
        logCapacityDetails(getName() + ": population ratio = " + popRatio);
        return capacity * popRatio;     
    }

    private double adjustCapacityForPeriodicSkew(double capacity)
    {
        DateTime now = timeService.getCurrentDateTime();
        int day = now.getDayOfWeek();  // 1=Monday, 7=Sunday
        int hour = now.getHourOfDay();  // 0-23
        
        double periodicSkew = capacityProfile.dailySkew[day-1] * capacityProfile.hourlySkew[hour];
        logCapacityDetails(getName() + ": periodic skew = " + periodicSkew);
        return capacity * periodicSkew;        
    }

    private double adjustCapacityForWeather(Timeslot timeslot, double capacity)
    {
        WeatherReport weather = weatherReportRepo.currentWeatherReport();
        log.info(getName() + ": weather = (" + weather.getTemperature() + ", " 
                + weather.getWindSpeed() + ", " + weather.getWindDirection() + ", " + weather.getCloudCover() + ")");
        
        double weatherFactor = 1.0;
        if (capacityProfile.temperatureInfluence == InfluenceKind.DIRECT) {
            int temperature = (int) Math.round(weather.getTemperature());
            weatherFactor = weatherFactor * capacityProfile.temperatureMap.get(temperature);
        }
        else if (capacityProfile.temperatureInfluence == InfluenceKind.DEVIATION) {
            int curr = (int) Math.round(weather.getTemperature());
            int ref = (int) Math.round(capacityProfile.temperatureReference);
            double deviationFactor = 1.0;
            if (curr > ref) {
                for (int t = ref+1; t <= curr; ++t) {
                    deviationFactor += capacityProfile.temperatureMap.get(t);
                }
            } else if (curr < ref) {
                for (int t = curr; t < ref; ++t) {
                    deviationFactor += capacityProfile.temperatureMap.get(t);
                }                
            }
            weatherFactor = weatherFactor * deviationFactor;
        }
        if (capacityProfile.windSpeedInfluence == InfluenceKind.DIRECT) {
            int windSpeed = (int) Math.round(weather.getWindSpeed());
            weatherFactor = weatherFactor * capacityProfile.windSpeedMap.get(windSpeed);
            if (windSpeed > 0.0 && capacityProfile.windDirectionInfluence == InfluenceKind.DIRECT) {
                int windDirection = (int) Math.round(weather.getWindDirection());
                weatherFactor = weatherFactor * capacityProfile.windDirectionMap.get(windDirection);
            }
        }
        if (capacityProfile.cloudCoverInfluence == InfluenceKind.DIRECT) {
            int cloudCover = (int) Math.round(weather.getCloudCover());
            weatherFactor = weatherFactor * capacityProfile.cloudCoverMap.get(cloudCover);
        }
        logCapacityDetails(getName() + ": weather factor = " + weatherFactor);
        return capacity * weatherFactor;
    }
    
    private double adjustCapacityForTariffRates(Timeslot timeslot, TariffSubscription subscription, double baseCapacity)
    {
        if ((baseCapacity - 0.0) < 0.01) return baseCapacity;
        
        double chargeForBase = subscription.getTariff().getUsageCharge(timeslot.getStartInstant(), 
                                                                       baseCapacity, subscription.getTotalUsage());
        double rateForBase = chargeForBase / baseCapacity;
        
        double benchmarkRate = capacityProfile.benchmarkRates.get(timeService.getHourOfDay());
        double rateRatio = rateForBase / benchmarkRate;

        double tariffRatesFactor = determineTariffRatesFactor(rateRatio);
        logCapacityDetails(getName() + ": tariff rates factor = " + tariffRatesFactor);
        return baseCapacity * tariffRatesFactor;
    }
	
    private double determineTariffRatesFactor(double rateRatio)
    {
        switch (capacityProfile.elasticityModelType) {
        case CONTINUOUS:
            return determineContinuousElasticityFactor(rateRatio);
        case STEPWISE:
            return determineStepwiseElasticityFactor(rateRatio);
        default: throw new Error("Unexpected elasticity model type: " + capacityProfile.elasticityModelType);
        }
    }
    
    private double determineContinuousElasticityFactor(double rateRatio)
    {
        double percentChange = (rateRatio - 1.0) / 0.01;
        double elasticityRatio = Double.parseDouble(capacityProfile.elasticityModelXml.getAttribute("ratio"));
        
        String range = capacityProfile.elasticityModelXml.getAttribute("range");
        String[] minmax = range.split("~");
        double low = Double.parseDouble(minmax[0]);
        double high = Double.parseDouble(minmax[1]);
        
        return Math.max(low, Math.min(high, 1.0 + (percentChange * elasticityRatio)));
    }
    
    private double determineStepwiseElasticityFactor(double rateRatio)
    {
        double[][] elasticity = null;
        if (elasticity == null) {
            elasticity = ParserFunctions.parseMapToDoubleArray(capacityProfile.elasticityModelXml.getAttribute("map"));
        }
        if (Math.abs(rateRatio - 1) < 0.01 || elasticity.length == 0) return 1.0;       
        if (capacityProfile.parentBundle.getCapacityType() == CapacityType.CONSUMPTION && rateRatio < 1.0) return 1.0;       
        if (capacityProfile.parentBundle.getCapacityType() == CapacityType.PRODUCTION && rateRatio > 1.0) return 1.0;
        
        final int RATE_RATIO_INDEX = 0;
        final int CAPACITY_FACTOR_INDEX = 1;
        double rateLowerBound = Double.NEGATIVE_INFINITY;
        double rateUpperBound = Double.POSITIVE_INFINITY;
        double lowerBoundCapacityFactor = 1.0;
        double upperBoundCapacityFactor = 1.0;
        for (int i=0; i < elasticity.length; ++i) {
            double r = elasticity[i][RATE_RATIO_INDEX];
            if (r <= rateRatio && r > rateLowerBound) {
                rateLowerBound = r;
                lowerBoundCapacityFactor = elasticity[i][CAPACITY_FACTOR_INDEX];
            }
            if (r >= rateRatio && r < rateUpperBound) {
                rateUpperBound = r;
                upperBoundCapacityFactor = elasticity[i][CAPACITY_FACTOR_INDEX];
            }
        }	
        return (rateRatio < 1) ? upperBoundCapacityFactor : lowerBoundCapacityFactor;
    }
    
    private double truncateTo2Decimals(double x)
    {
        double fract, whole;
        if (x > 0) {
            whole = Math.floor(x);
            fract = Math.floor((x - whole) * 100) / 100;
        } else {
            whole = Math.ceil(x);
            fract = Math.ceil((x - whole) * 100) / 100;
        }
        return whole + fract;
    }

    private void logCapacityDetails(String msg) 
    {
        log.info(msg);
        //log.debug(msg);
    }
    
    private String getName() 
    {
        return customerProfile.name;
    }    
}

