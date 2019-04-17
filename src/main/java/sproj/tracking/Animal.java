package sproj.tracking;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.commons.math3.filter.KalmanFilter;
import org.bytedeco.javacpp.opencv_core.Scalar;
import sproj.assignment.OptimalAssigner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Class used to record the motion data of individual
 * subject animals during tracking
 */
public class Animal {

    private static final boolean DEBUG = false;
    
    // detailed explanation of this value is in OptimalAssigner class 
    private final double DEFAULT_COST_OF_NON_ASSIGNMENT = OptimalAssigner.DEFAULT_COST_OF_NON_ASSIGNMENT;

    public static final int LINE_THICKNESS = 2;
    public static final int CIRCLE_RADIUS = 15;

    private static final int LINE_POINTS_SIZE = 72;
    private CircularFifoQueue<int[]> linePoints;

    private static final int DATA_BUFFER_ARRAY_SIZE = 60;
    private ArrayList<double[]> dataPoints;

    private KalmanFilter trackingFilter;

    private double vx, vy;
    private double ax, ay;   // acceleration
    private double currentHeading;
    private int[] positionBounds;        // x1, x2, y1, y2

    public int x, y;
    public Scalar color;

    // count of consecutive time steps that have not had true assignment updates
    private int timeStepsPredicted;

    // dynamic cost value that increases with `timeStepsPredicted`
    private double currCostNonAssignnmnt;

    // maximum cost of assignment allowed
    private int MAXCOST;

    // number of consecutive frames of trajectory prediction allowed
    // after this number, prediction is stopped and current cost is set to MAXCOST
    // to allow Animal instance to snap to the nearest unassigned bounding box
    private final int MAX_FRAMES_PREDICT = 30; // 1 second (30 fps)

    // found through experimentation / trial & error
    // todo --> calculate more exact value using max displacement & pixel size of tadpoles
    private static final int MAX_VELOCITY = 80;

    private boolean PREDICT_WITH_VELOCITY = true;

    public Animal(int _x, int _y, final int[] positionBounds, final Scalar clr, KalmanFilter kFilter) {
        this.x = _x; this.y = _y;
        this.positionBounds = positionBounds;
        currentHeading = 0;
        color = clr; // new Scalar(clr[0], clr[1], clr[2], 1.0);
        linePoints = new CircularFifoQueue<>(LINE_POINTS_SIZE);
        dataPoints = new ArrayList<>(DATA_BUFFER_ARRAY_SIZE);
        trackingFilter = kFilter;

        // maximum possible distance, the diagonal length of frame
        MAXCOST = (int) Math.round(Math.pow(
                Math.pow(positionBounds[1]-positionBounds[0], 2) +
                Math.pow(positionBounds[3]-positionBounds[2], 2), 0.5
        ));

        this.timeStepsPredicted = 0;
        this.currCostNonAssignnmnt = DEFAULT_COST_OF_NON_ASSIGNMENT;
    }

    @Override
    public String toString() {
        return String.format("animal at [%d,%d] with color %s", this.x, this.y, this.color.toString());
    }

    public void setCurrCost(final double val) {
        currCostNonAssignnmnt = val;
    }

    public double getCurrCost() {
        return currCostNonAssignnmnt;
    }

    public void clearPoints() {     // todo check impact on performance of calling this
        this.dataPoints.clear();    // obviously the time requirement is directly proportional to the size of the array,
    }                               // since this nullifies all the array elements, so keep the array size relatively small  

    /**
     * Called once per timestep, after optimal assignments have been solved
     *
     * Note that the order of parameters to this function does not match
     * the order in which the data points are written to file
     *
     * @param _x
     * @param _y
     * @param dt
     * @param timePos
     * @param isPredicted
     */
    public void updateLocation(int _x, int _y, double dt, long timePos, boolean isPredicted) {

        if (isPredicted) {
            timeStepsPredicted++;

            currCostNonAssignnmnt = (timeStepsPredicted >= MAX_FRAMES_PREDICT) ? MAXCOST
                    : (currCostNonAssignnmnt + timeStepsPredicted) % MAXCOST;

            if (DEBUG) {System.out.println("Current cost: " + currCostNonAssignnmnt);}

        } else {
            timeStepsPredicted = 0;
            currCostNonAssignnmnt = DEFAULT_COST_OF_NON_ASSIGNMENT;
        }

        // calculate a probability of correctness of current position
        double accuracyProb = (!isPredicted) ? 1.0
                : (timeStepsPredicted >= MAX_FRAMES_PREDICT) ? 0.0
                    : 1 - (timeStepsPredicted / (double) MAX_FRAMES_PREDICT);

        this.x = _x; this.y = _y;
        applyBoundsConstraints();
        dataPoints.add(new double[]{timePos, this.x, this.y, accuracyProb});
        linePoints.add(new int[]{this.x, this.y});   // calls the addFirst() method, adds to front of Deque
        updateVelocity(dt);
        updateKFilter();
    }


    private void applyBoundsConstraints() {
        x = (x>positionBounds[0]) ? x : positionBounds[0];
        x = (x<positionBounds[1]) ? x : positionBounds[1];
        y = (y>positionBounds[2]) ? y : positionBounds[2];
        y = (y<positionBounds[3]) ? y : positionBounds[3];
    }

    public void predictTrajectory(double dt, long timePos) {

        double[] predictedState = getPredictedState();

        if (DEBUG) {
            System.out.println(String.format("Current [(%d,%d)(%.3f,%.3f)], estimation: %s",
                    this.x, this.y, this.vx, this.vy, Arrays.toString(predictedState))
            );
        }

        double predX = predictedState[0]; //(int) Math.round(predictedState[0]);
        double predY = predictedState[1]; //(int) Math.round(predictedState[1]);
        double vx = predictedState[2];
        double vy = predictedState[3];

        int newx, newy;     // prevent Animal instance from predicting velocity for too long
        if (PREDICT_WITH_VELOCITY && timeStepsPredicted < MAX_FRAMES_PREDICT) {

            /* TODO if movementstate.stationary: just use predicted x & y */

            double displThresh = 10.0;

            newx = (int) Math.round(this.x + (vx * dt));
            newy = (int) Math.round(this.y - (vy * dt));
            newx = (Math.abs(newx - predX) > displThresh) ? (int) Math.round(predX) : newx;
            newy = (Math.abs(newy - predY) > displThresh) ? (int) Math.round(predY) : newy;

        } else {         // Simplest method
            newx = (int) Math.round(predX);
            newy = (int) Math.round(predY);
        }

        if (DEBUG) {System.out.println(String.format("new coordinates: (%d, %d)", newx, newy));}

        // alternative method:  use predicted position to calculate heading, and then factor in velocity to predict new position

        updateLocation(newx, newy, dt, timePos, true);
    }


    /** Use line points array because it keeps track of the most recent points */
    private void updateVelocity(double dt) {        // TODO use timePos points to calculate dt!

        int subtractionIdx = 3;  // calculate velocity over the last N frames
        if (linePoints.size() < subtractionIdx + 1) {
            this.vx = 0;
            this.vy = 0;

        } else {
            // todo average these out so the change in values isnt so drastic
            int[] prevPoint = linePoints.get(linePoints.size() - 1 - subtractionIdx);   // the most recent point is at the end index
            this.vx = (this.x - prevPoint[0]) / (subtractionIdx * dt);
            /* flip the subtraction because y axis in graphics increases by going down instead of up */
            this.vy = ((prevPoint[1] - this.y) / (subtractionIdx * dt));
        }

        this.vx = (Math.abs(this.vx) <= MAX_VELOCITY) ? this.vx : Math.signum(this.vx) * MAX_VELOCITY;
        this.vy = (Math.abs(this.vy) <= MAX_VELOCITY) ? this.vy : Math.signum(this.vy) * MAX_VELOCITY;
    }

    private void updateKFilter() {
        double[] stateCorrection = new double[]{this.x, this.y, this.vx, this.vy, this.ax, this.ay};
        this.trackingFilter.predict();      // this needs to be called before calling correct()
        if (DEBUG) {System.out.println(String.format("\nUpdating filter: %d %d %.4f %.4f", this.x, this.y, this.vx, this.vy));}
        this.trackingFilter.correct(stateCorrection);
        if (DEBUG) {System.out.println(String.format("Prediction: %s", Arrays.toString(this.trackingFilter.getStateEstimation())));}
    }

    private double[] getPredictedState() {
        this.trackingFilter.predict();
        return this.trackingFilter.getStateEstimation();
//        double[] predictedState = this.trackingFilter.getStateEstimation();
//        this.trackingFilter.correct(predictedState);        // this is already called when predictTrajectory() calls updateLocation
//        return predictedState;
    }

    private enum MovementState {
        INMOTION, STATIONARY, STARTLED
    }

    public Iterator<int[]> getLinePointsIterator() {      // TODO  figure out how to use this instead?   -->   use linePoints.size() to know when to stop
        return linePoints.iterator();
    }

    public Iterator<double[]> getDataPointsIterator() {      // TODO  figure out how to use this instead?   -->   use linePoints.size() to know when to stop
        return dataPoints.iterator();
    }
}
