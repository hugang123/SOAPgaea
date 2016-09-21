/**
 * Copyright (c) 2011, BGI and/or its affiliates. All rights reserved.
 * BGI PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package org.bgi.flexlab.gaea.utils.math;

/**
 * Fisher精确检验
 * @author ZhangYong
 *
 */
public class FisherTest {
	/**
	 * fisher检验表格：a, b, c, d值
	 */
	private int[] fisherTable = new int[4];
	
	/**
	 * 阶乘log值数组
	 */
	private double[] logFactorial;
	
	/**
	 * fisher初始化
	 *             fw      rc
     *   allele1   #a      #b
     *   allele2   #c      #d
	 * @param sbstrand
	 * @param ref
	 * @param alter
	 */
	public void FisherTestInit(int[] sbstrand, int ref, String alter) {
		//初始化abcd数组
		fisherTable[0] = sbstrand[ref];
		fisherTable[1] = sbstrand[ref + 4];
		
		if(alter.length() == 1) {//纯合
			int al = ((alter.charAt(0) >> 1) & 0x03);
			fisherTable[2] = sbstrand[al];
			fisherTable[3] = sbstrand[al + 4];
		} else {//杂合
			int al1 = ((alter.charAt(0) >> 1) & 0x03);
			int al2 = ((alter.charAt(2) >> 1) & 0x03);
			
			fisherTable[2] = sbstrand[al1] + sbstrand[al2];
			fisherTable[3] = sbstrand[al1 + 4] + sbstrand[al2 + 4];
		}
		//计算log阶乘
		int m = 0;
		for(int i = 0; i < 4; i++) {
			m += fisherTable[i];
		}
		
		logFactorial = new double[m + 1];
		
		logFactorial[0] = 0.0;
        for (int i = 1; i <= m; i++) {
            logFactorial[i] = logFactorial[i-1] + Math.log(i);
        }
	}
	
	public void FisherTestInitTest() {
		//计算log阶乘
		int m = 0;
		for(int i = 0; i < 4; i++) {
			m += fisherTable[i];
		}
		
		logFactorial = new double[m + 1];
				
		logFactorial[0] = 0.0;
		for (int i = 1; i <= m; i++) {
		    logFactorial[i] = logFactorial[i-1] + Math.log(i);
		}
	}
	
	/**
	 * 计算P值
	 * @param a
	 * @param b
	 * @param c
	 * @param d
	 * @return
	 */
	private double fisherSub(int a, int b, int c, int d) {
        return Math.exp(logFactorial[a + b] +
                        logFactorial[c + d] +
                        logFactorial[a + c] +
                        logFactorial[b + d] -
                        logFactorial[a + b + c + d] -
                        logFactorial[a] -
                        logFactorial[b] -
                        logFactorial[c] -
                        logFactorial[d]);
    }
	
	/**
	 * fisher 检验
	 * @return pSum 
	 */
	public double fisher() {
		 int a = fisherTable[0];
		 int b = fisherTable[1];
		 int c = fisherTable[2];
		 int d = fisherTable[3];
		 
		 //生成频数表格,保证a最小
		 if((a > b && c > b) || (c > d && a > d)) {
			 int tmp;
			 //a,b 交换
			 tmp = a;
			 a = b;
			 b = tmp;
			 //c,d交换
			 tmp = c;
			 c = d;
			 d = tmp;
		 }
		 if(a > c) {
			 int tmp;
			 //a,c交换
			 tmp = c;
			 c = a;
			 a =tmp;
			 //b,d交换
			 tmp = d;
			 d = b;
			 b = tmp;
		 }
		 
		 int tableNmber = 0;
		 if((a + c) > (a + b)) {
			 tableNmber = a + b + 1;
		 } else {
			 tableNmber = a + c + 1;
		 }
		 
		 //计算D*和P*
		 int aOrg = a;
	     double[] pAll = new double[tableNmber];
	     int DOrg = Math.abs(a*d - b*c);
	     
	     a = 0; b += aOrg; c = (tableNmber - 1); d = logFactorial.length - (a + b + c) - 1;
	     for(int i = 0; i < tableNmber; i++) {
	    	 int Di = a*d - b*c;
	    	 if(Math.abs(Di) >= DOrg) {
	    		 pAll[i] = fisherSub(a, b, c, d);
	    	 } else {
	    		 pAll[i] = 0;
	    	 }
	    	 a ++; b--; c--;d++;
	     }
	     
	     double pSum = 0.0d;
	     for(int i = 0; i < tableNmber; i++) {
	    	 if(pAll[i] <= pAll[aOrg]) {
	    		 //System.out.println("pvalue:" + pAll[i]);
	    		 pSum += pAll[i];
	    	 }
	     }
	     return pSum;
	}

	public int[] getFisherTable() {
		return fisherTable;
	}

	public void setFisherTable(int[] fisherTable) {
		this.fisherTable = fisherTable;
	}
}