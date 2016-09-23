package org.bgi.flexlab.gaea.data.structure.region;

import java.util.HashMap;
import java.util.Map;

import org.bgi.flexlab.gaea.data.structure.region.SingleRegion.Regiondata;
import org.bgi.flexlab.gaea.util.posInfo.IntPosInfo;

public abstract class SingleRegionReport<T extends SingleRegionStatistic> {
	protected SingleRegion singleReigon;
	protected StringBuffer outputString;
	protected Map<SingleRegion.Regiondata, T> result = new HashMap<SingleRegion.Regiondata, T>();
	
	public SingleRegionReport(SingleRegion singleReigon) {
		this.singleReigon = singleReigon;
		outputString = new StringBuffer();
	}
	
	public String toReducerString() {
		return outputString.toString();
	}
	
	public String getStatisticString(String chrName, int winStart, int windowSize, IntPosInfo deep, String title) {
		int start, end, index = -1;
		start = 0;
		end = windowSize - 1;
		int i = start;
		
		while(i <= end) {//�ҵ���һ��index
			if((index = singleReigon.posInRegion(chrName, i + winStart)) >= 0) {
				break;
			}
			else {
				i++;
			}
		}
		if(index >= 0) {
			//��֤����δ����Ⱦɫ�巶Χ��ͬʱ��һ������Ŀ�ʼС��binĩβ����������bin��ʼ
			while(withinChrAndBin(index, winStart, windowSize, chrName)) {
				//��������,���ܻ���ֻ��һ����������Ĳ���
				int regionStart = singleReigon.getRegion(index).getStart();
				int regionEnd = singleReigon.getRegion(index).getEnd();
				//System.err.println("index:" + index + "\tstart-end:" +regionStart+"-"+regionEnd + "\tposition:"+i +"\t" + winStart);
				if(regionStart >= winStart && regionEnd <= winStart + windowSize -1) {
					outputString.append(title + "single Region Statistic:\n" + index + ":");
					getWholeRegionInfo(deep, regionStart - winStart, regionEnd - winStart);
				} else if(regionStart >= winStart && regionEnd > winStart + windowSize - 1) { 
					outputString.append(title + "part single Region Statistic:\n" + index + ":");
					getPartRegionInfo(deep, regionStart - winStart, windowSize - 1);
				} else {
					outputString.append(title + "part single Region Statistic:\n" + index + ":");
					if(regionEnd- winStart >= windowSize) {
						getPartRegionInfo(deep, 0, windowSize - 1);
					} else {
						getPartRegionInfo(deep, 0, regionEnd- winStart);
					}
				}
				index++;
			}
		}
		return outputString.toString();
	}
	
	private boolean withinChrAndBin(int index, int winStart, int windowSize, String chrName) {
		return index < singleReigon.getChrInterval(chrName)[1] && 
				singleReigon.getRegion(index).getStart() < winStart + windowSize && 
				singleReigon.getRegion(index).getEnd() >= winStart;
	}
	public Map<Regiondata, T> getResult() {
		return result;
	}
	
	public abstract String getWholeRegionInfo(IntPosInfo deep, int start, int end);
	
	public abstract String getPartRegionInfo(IntPosInfo deep, int start, int end);
		
	public abstract void parseReducerOutput(String line, boolean isPart);
}