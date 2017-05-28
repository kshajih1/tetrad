///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (C) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
// 2007, 2008, 2009, 2010, 2014, 2015 by Peter Spirtes, Richard Scheines, Joseph   //
// Ramsey, and Clark Glymour.                                                //
//                                                                           //
// This program is free software; you can redistribute it and/or modify      //
// it under the terms of the GNU General Public License as published by      //
// the Free Software Foundation; either version 2 of the License, or         //
// (at your option) any later version.                                       //
//                                                                           //
// This program is distributed in the hope that it will be useful,           //
// but WITHOUT ANY WARRANTY; without even the implied warranty of            //
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             //
// GNU General Public License for more details.                              //
//                                                                           //
// You should have received a copy of the GNU General Public License         //
// along with this program; if not, write to the Free Software               //
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA //
///////////////////////////////////////////////////////////////////////////////

package edu.cmu.tetrad.search;

import edu.cmu.tetrad.data.*;
import edu.cmu.tetrad.graph.*;
import edu.cmu.tetrad.regression.RegressionDataset;
import edu.cmu.tetrad.regression.RegressionResult;
import org.apache.commons.math3.distribution.TDistribution;

import java.awt.*;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static edu.cmu.tetrad.util.StatUtils.*;
import static java.lang.Math.*;
import static java.lang.Math.max;

/**
 * Fast adjacency search followed by robust skew orientation. Checks are done for adding
 * two-cycles. The two-cycle checks do not require non-Gaussianity. The robust skew
 * orientation of edges left or right does.
 *
 * @author Joseph Ramsey
 */
public final class EFang implements GraphSearch {

    // Elapsed time of the search, in milliseconds.
    private long elapsed = 0;

    // The data sets being analyzed. They must all have the same variables and the same
    // number of records.
    private DataSet dataSet = null;

    // For the Fast Adjacency Search.
    private int depth = -1;

    // For the SEM BIC score, for the Fast Adjacency Search.
    private double penaltyDiscount = 1;

    // Alpha for orienting 2-cycles. Usually needs to be low.
    private double alpha = .3;

    // Knowledge the the search will obey, of forbidden and required edges.
    private IKnowledge knowledge = new Knowledge2();

    // Cutoff for x.
    private double x0 = 0.0;

    // Cutoff for y.
    private double y0 = 0.0;

    // Whether variables should be multiplied by the signs of the skewnesses.
    private boolean empirical = true;

    // Whether RSkew should be used.
    private boolean rskew = false;

    /**
     * @param dataSet These datasets must all have the same variables, in the same order.
     */
    public EFang(DataSet dataSet) {
        this.dataSet = dataSet;
    }

    //======================================== PUBLIC METHODS ====================================//

    /**
     * Runs the search on the concatenated data, returning a graph, possibly cyclic, possibly with
     * two-cycles. Runs the fast adjacency search (FAS, Spirtes et al., 2000) follows by a modification
     * of the robust skew rule (Pairwise Likelihood Ratios for Estimation of Non-Gaussian Structural
     * Equation Models, Smith and Hyvarinen), together with some heuristics for orienting two-cycles.
     *
     * @return the graph. Some of the edges may be undirected (though it shouldn't be many in most cases)
     * and some of the adjacencies may be two-cycles.
     */
    public Graph search() {
        long start = System.currentTimeMillis();

        DataSet dataSet = DataUtils.standardizeData(this.dataSet);

        RegressionDataset regression = new RegressionDataset(dataSet);

        SemBicScore score = new SemBicScore(new CovarianceMatrixOnTheFly(dataSet));
        score.setPenaltyDiscount(penaltyDiscount);
        IndependenceTest test = new IndTestScore(score, dataSet);
        List<Node> variables = dataSet.getVariables();

        double[][] colData = dataSet.getDoubleData().transpose().toArray();

        System.out.println("FAS");

        FasStable fas = new FasStable(test);
        fas.setDepth(getDepth());
        fas.setVerbose(false);
        fas.setKnowledge(knowledge);
        Graph G0 = fas.search();

        SearchGraphUtils.pcOrientbk(knowledge, G0, G0.getNodes());

        System.out.println("Orientation");

        Graph graph = new EdgeListGraph(variables);

        for (int i = 0; i < colData.length; i++) {
            double skewness = skewness(colData[i]);

            if (skewness < -.4) {
                for (int k = 0; k < colData[i].length; k++) {
                    colData[i][k] *= -1;
                }
            }
        }

        for (Edge edge : G0.getEdges()) {
            Node X = edge.getNode1();
            Node Y = edge.getNode2();

            int i = variables.indexOf(X);
            int j = variables.indexOf(Y);

            if (graph.isAdjacentTo(X, Y)) continue;

            // Standardized.

            double sum = 0.0;
            int nn = 0;

            double[] x = colData[i];
            double[] y = colData[j];

            double[] c5 = cov(x, y, 1, 0);
            double[] c6 = cov(x, y, 0, 1);

            List<Double> sList = new ArrayList<>();

            for (int k = 0; k < x.length; k++) {
                double _xy = x[k] * y[k];

                double d1 = (x[k] > 0 ? _xy : 0);
                double d2 = (y[k] > 0 ? _xy : 0);

                if (d1 - d2 != 0) {
                    sum += d1 - d2;
                    nn++;

                    sList.add(d1 - d2);
                }
            }

            double e = sum / nn;

            double[] _s = new double[sList.size()];

            for (int k = 0; k < _s.length; k++) {
                _s[k] = sList.get(k);
            }

            double t = (e) / (sd(_s) / sqrt(nn));
            double p = 2 * (1.0 - new TDistribution(nn - 1).cumulativeProbability(abs(t / 2)));

            double rho = correlation(x, y);

            double R = rho * (c5[0] - c6[0]);

            if (knowledgeOrients(X, Y)) {
                graph.addDirectedEdge(X, Y);
            } else if (knowledgeOrients(Y, X)) {
                graph.addDirectedEdge(Y, X);
            } else if (p > alpha) {
                Edge edge1 = Edges.directedEdge(X, Y);
                Edge edge2 = Edges.directedEdge(Y, X);

                edge1.setLineColor(Color.GREEN);
                edge2.setLineColor(Color.GREEN);

                graph.addEdge(edge1);
                graph.addEdge(edge2);
            } else if (R > 0) {
                graph.addDirectedEdge(X, Y);
            } else if (R < 0) {
                graph.addDirectedEdge(Y, X);
            } else {
                graph.addUndirectedEdge(X, Y);
            }
        }

        printHistogram(dataSet, regression, graph);

        System.out.println();
        System.out.println("Done");

        long stop = System.currentTimeMillis();
        this.elapsed = stop - start;

        return graph;
    }

    private void printHistogram(DataSet dataSet, RegressionDataset regression, Graph graph) {
        double sigma = Math.pow(6.0 / dataSet.getNumRows(), 0.5);

        double[] cutoffs = new double[]{-10, -3, -2, -1.5, -1, -0.5, 0,
                .5, 1, 1.5, 2, 3, 10};
        int[] counts = new int[cutoffs.length];
        int total = 0;

        double sumSkew = 0.0;
        double sd = sqrt(6.0 / dataSet.getNumRows());

        for (Node x : graph.getNodes()) {
            RegressionResult result = regression.regress(x, graph.getParents(x));
            double[] residuals = result.getResiduals().toArray();
            double skewness = skewness(residuals);
            System.out.println("Node " + x + " skewness(residual) = " + (skewness) + " parents = " + graph.getParents(x));

            double skewnessX = skewness(dataSet.getDoubleData().getColumn(dataSet.getColumn(x)).toArray());
            System.out.println("Node " + x + " skewness(node) = " + (skewnessX));

//            for (Node y : graph.getParents(x)) {
//                double[] _x = dataSet.getDoubleData().getColumn(dataSet.getColumn(x)).toArray();
//                double[] _y = dataSet.getDoubleData().getColumn(dataSet.getColumn(y)).toArray();
//
//                double rho = correlation(_x, _y);
//                System.out.println(y + "--> " + x + " rho = " + rho);
//            }

            System.out.println();

            for (int i = 0; i < cutoffs.length; i++) {
                if (skewness < cutoffs[i] * sigma) {
                    counts[i]++;
                }
            }

            total++;
            sumSkew += skewness;
        }


        double avgSkew = sumSkew / (double) total;

        NumberFormat nf = new DecimalFormat("0.000");

        for (int i = 0; i < cutoffs.length; i++) {
            double number = counts[i] / (double) total;

            System.out.printf("\nBelow %5.1f (= %6.3f) Percent = %5.3f, 1 - Percent = %5.3f", cutoffs[i],
                    cutoffs[i] * sigma, number, 1.0 - number);

//            System.out.println("Below " + nf.format(cutoffs[i]) + " * sigma % = " + nf.format(number)
//                    + "   1 - % = " + nf.format(1.0 -  number));
        }

        System.out.println();
        System.out.println();
        System.out.println("Avg skew " + avgSkew);
        System.out.println("N = " + dataSet.getNumRows());
        System.out.println("Sigma = " + nf.format(sigma));
    }

    private double[] cov(double[] x, double[] y, int xInc, int yInc) {
        double exy = 0.0;
        double exyxy = 0.0;
        double exx = 0.0;
        double eyy = 0.0;

        double ex = 0.0;
        double ey = 0.0;

        double egx = 0.0;
        double egy = 0.0;

        int n = 0;

        for (int k = 0; k < x.length; k++) {
            if (xInc == 0 && yInc == 0) {
                exy += x[k] * y[k];
                exyxy += x[k] * y[k] * x[k] * y[k];
                exx += x[k] * x[k];
                eyy += y[k] * y[k];
                ex += x[k];
                ey += y[k];
                egx += g(x[k]);
                egy += g(y[k]);
                n++;
            } else if (xInc == 1 && yInc == 0) {
                if (x[k] > x0) {
                    exy += x[k] * y[k];
                    exyxy += x[k] * y[k] * x[k] * y[k];
                    exx += x[k] * x[k];
                    eyy += y[k] * y[k];
                    ex += x[k];
                    ey += y[k];
                    egx += g(x[k]);
                    egy += g(y[k]);
                    n++;
                }
            } else if (xInc == 0 && yInc == 1) {
                if (y[k] > y0) {
                    exy += x[k] * y[k];
                    exyxy += x[k] * y[k] * x[k] * y[k];
                    exx += x[k] * x[k];
                    eyy += y[k] * y[k];
                    ex += x[k];
                    ey += y[k];
                    egx += g(x[k]);
                    egy += g(y[k]);
                    n++;
                }
            } else if (xInc == -1 && yInc == 0) {
                if (x[k] < x0) {
                    exy += x[k] * y[k];
                    exyxy += x[k] * y[k] * x[k] * y[k];
                    exx += x[k] * x[k];
                    eyy += y[k] * y[k];
                    ex += x[k];
                    ey += y[k];
                    egx += g(x[k]);
                    egy += g(y[k]);
                    n++;
                }
            } else if (xInc == 0 && yInc == -1) {
                if (y[k] < y0) {
                    exy += x[k] * y[k];
                    exyxy += x[k] * y[k] * x[k] * y[k];
                    exx += x[k] * x[k];
                    eyy += y[k] * y[k];
                    ex += x[k];
                    ey += y[k];
                    egx += g(x[k]);
                    egy += g(y[k]);
                    n++;
                }
            }
        }

        n = x.length;

        exx /= n;
        eyy /= n;
        exy /= n;
        exyxy /= n;
        ex /= n;
        ey /= n;
        egx /= x.length;
        egy /= x.length;

        double sxy = exy - ex * ey;
        double sx = sqrt(exx - ex * ex);
        double sy = sqrt(eyy - ey * ey);

        return new double[]{exy, exx, eyy, exyxy, sxy / (sx * sy), sx * sx, sy * sy, (double) n, egx, egy};
    }

    private double g(double x) {
        return log(cosh(max(0, x)));
    }

    /**
     * @return The depth of search for the Fast Adjacency Search (FAS).
     */

    public int getDepth() {
        return depth;
    }

    /**
     * @param depth The depth of search for the Fast Adjacency Search (S). The default is -1.
     *              unlimited. Making this too high may results in statistical errors.
     */
    public void setDepth(int depth) {
        this.depth = depth;
    }

    /**
     * @return The elapsed time in milliseconds.
     */
    public long getElapsedTime() {
        return elapsed;
    }

    /**
     * @return Returns the penalty discount used for the adjacency search. The default is 1,
     * though a higher value is recommended, say, 2, 3, or 4.
     */
    public double getPenaltyDiscount() {
        return penaltyDiscount;
    }

    /**
     * @param penaltyDiscount Sets the penalty discount used for the adjacency search.
     *                        The default is 1, though a higher value is recommended, say,
     *                        2, 3, or 4.
     */
    public void setPenaltyDiscount(double penaltyDiscount) {
        this.penaltyDiscount = penaltyDiscount;
    }

    /**
     * @param alpha Alpha for orienting 2-cycles. Needs to be on the low side usually. Default 1e-6.
     */
    public void setAlpha(double alpha) {
        this.alpha = alpha;
    }

    /**
     * @return X cutoff.
     */
    public double getX0() {
        return x0;
    }

    /**
     * @param x0 X cutoff.
     */
    public void setX0(double x0) {
        this.x0 = x0;
    }

    /**
     * @return the current knowledge.
     */
    public IKnowledge getKnowledge() {
        return knowledge;
    }

    /**
     * @param knowledge Knowledge of forbidden and required edges.
     */
    public void setKnowledge(IKnowledge knowledge) {
        this.knowledge = knowledge;
    }

    //======================================== PRIVATE METHODS ====================================//

    private boolean knowledgeOrients(Node left, Node right) {
        return knowledge.isForbidden(right.getName(), left.getName()) || knowledge.isRequired(left.getName(), right.getName());
    }

    public boolean isRskew() {
        return rskew;
    }

    public void setRskew(boolean rskew) {
        this.rskew = rskew;
    }
}






