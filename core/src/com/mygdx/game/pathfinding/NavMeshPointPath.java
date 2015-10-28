package com.mygdx.game.pathfinding;

import com.badlogic.gdx.ai.pfa.Connection;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;

import java.util.Iterator;

/**
 * Created by Johannes Sjolund on 10/24/15.
 */
public class NavMeshPointPath implements Iterable<Vector3> {

	/**
	 * A point where an edge on the navmesh is crossed.
	 */
	private class EdgePoint {
		/**
		 * Triangle which must be crossed to reach the next path point.
		 */
		public Triangle toNode;
		/**
		 * Triangle which was crossed to reach this point.
		 */
		public Triangle fromNode;
		/**
		 * Path edges connected to this point.
		 * Can be used for spline generation at some point perhaps...
		 */
		public Array<Edge> connectingEdges = new Array<Edge>();
		/**
		 * The point where the path crosses an edge.
		 */
		public Vector3 point;

		public EdgePoint(Vector3 point, Triangle toNode) {
			this.point = point;
			this.toNode = toNode;
		}
	}

	/**
	 * Plane funnel for the Simple Stupid Funnel Algorithm
	 */
	private class Funnel {

		public final Plane leftPlane = new Plane();
		public final Plane rightPlane = new Plane();
		public final Vector3 leftPortal = new Vector3();
		public final Vector3 rightPortal = new Vector3();
		public final Vector3 pivot = new Vector3();

		public void setLeftPlane(Vector3 pivot, Vector3 leftEdgeVertex) {
			leftPlane.set(pivot, tmp1.set(pivot).add(up), leftEdgeVertex);
			leftPortal.set(leftEdgeVertex);
		}

		public void setRightPlane(Vector3 pivot, Vector3 rightEdgeVertex) {
			rightPlane.set(pivot, tmp1.set(pivot).add(up), rightEdgeVertex);
			rightPlane.normal.scl(-1);
			rightPlane.d = -rightPlane.d;
			rightPortal.set(rightEdgeVertex);
		}

		public void setPlanes(Vector3 pivot, Edge edge) {
			setLeftPlane(pivot, edge.leftVertex);
			setRightPlane(pivot, edge.rightVertex);
		}

		public Plane.PlaneSide sideLeftPlane(Vector3 point) {
			return leftPlane.testPoint(point);
		}

		public Plane.PlaneSide sideRightPlane(Vector3 point) {
			return rightPlane.testPoint(point);
		}
	}

	private final Plane crossingPlane = new Plane();
	private final Vector3 tmp1 = new Vector3();
	private final Vector3 tmp2 = new Vector3();
	private final Vector3 tmp3 = new Vector3();
	private Array<Connection<Triangle>> nodes;
	private Vector3 up = Vector3.Y;
	private Vector3 start;
	private Vector3 end;
	private Triangle startTri;
	private EdgePoint lastPointAdded;
	private Array<Vector3> vectors = new Array<Vector3>();
	private Array<EdgePoint> pathPoints = new Array<EdgePoint>();
	private Edge nodelLastEdge;

	@Override
	public Iterator<Vector3> iterator() {
		return vectors.iterator();
	}

	private Edge getEdge(int index) {
		return (Edge) ((index == nodes.size) ? nodelLastEdge : nodes.get(index));
	}

	private int numEdges() {
		return nodes.size + 1;
	}

	/**
	 * Calculate the shortest path through the navigation mesh triangles.
	 *
	 * @param trianglePath
	 */
	public void calculateForGraphPath(NavMeshGraphPath trianglePath) {
		clear();
		nodes = trianglePath.nodes;
		this.start = new Vector3(trianglePath.start);
		this.end = new Vector3(trianglePath.end);
		this.startTri = trianglePath.startTri;

		// Check that the start point is actually inside the start triangle, if not, project it to the closest
		// triangle edge. Otherwise the funnel calculation might generate spurious path segments.
		Ray ray = new Ray(tmp1.set(up).scl(1000).add(start), tmp2.set(up).scl(-1));
		if (!Intersector.intersectRayTriangle(ray, startTri.a, startTri.b, startTri.c, null)) {
			float minDst = Float.MAX_VALUE;
			Vector3 projection = new Vector3();
			Vector3 newStart = new Vector3();
			for (int i = 0; i <= 2; i++) {
				Vector3 a = startTri.corners.get(i);
				Vector3 b = (i == 2) ? startTri.corners.get(0) : startTri.corners.get(i + 1);
				float dst = calculatePointSegmentSquareDistance(projection, a, b, start);
				if (dst < minDst) {
					newStart.set(projection);
					minDst = dst;
				}
			}
			start.set(newStart);
		}
		if (nodes.size == 0) {
			addPoint(start, startTri);
			addPoint(end, startTri);
		} else {
			nodelLastEdge = new Edge(nodes.get(nodes.size - 1).getToNode(), nodes.get(nodes.size - 1).getToNode(), end, end);
			calculateEdgePoints();
		}
	}

	/**
	 * Clear the stored path data.
	 */
	public void clear() {
		vectors.clear();
		pathPoints.clear();
		start = null;
		end = null;
		startTri = null;
		lastPointAdded = null;
		nodelLastEdge = null;
	}

	/**
	 * A path point which crosses one or more edges in the navigation mesh.
	 *
	 * @param index
	 * @return
	 */
	public Vector3 getVector(int index) {
		return vectors.get(index);
	}

	/**
	 * The number of path points.
	 *
	 * @return
	 */
	public int getSize() {
		return vectors.size;
	}

	/**
	 * All vectors in the path.
	 *
	 * @return
	 */
	public Array<Vector3> getVectors() {
		return vectors;
	}

	/**
	 * The triangle which must be crossed to reach the next path point.
	 *
	 * @param index
	 * @return
	 */
	public Triangle getToTriangle(int index) {
		return pathPoints.get(index).toNode;
	}

	/**
	 * The triangle from which must be crossed to reach this point.
	 *
	 * @param index
	 * @return
	 */
	public Triangle getFromTriangle(int index) {
		return pathPoints.get(index).fromNode;
	}

	/**
	 * The navmesh edge(s) crossed at this path point.
	 *
	 * @param index
	 * @return
	 */
	public Array<Edge> getCrossedEdges(int index) {
		return pathPoints.get(index).connectingEdges;
	}

	private void addPoint(Vector3 point, Triangle toNode) {
		addPoint(new EdgePoint(point, toNode));
	}

	private void addPoint(EdgePoint edgePoint) {
		vectors.add(edgePoint.point);
		pathPoints.add(edgePoint);
		lastPointAdded = edgePoint;
	}

	/**
	 * Calculate the shortest point path through the path triangles, using the Simple Stupid Funnel Algorithm.
	 *
	 * @return
	 */
	private void calculateEdgePoints() {
		Edge edge = getEdge(0);
		addPoint(start, edge.fromNode);
		lastPointAdded.fromNode = edge.fromNode;

		Funnel funnel = new Funnel();
		funnel.pivot.set(start);
		funnel.setPlanes(funnel.pivot, edge);

		int leftIndex = 0;
		int rightIndex = 0;
		int lastRestart = 0;

		for (int i = 1; i < numEdges(); ++i) {
			edge = getEdge(i);

			Plane.PlaneSide leftPlaneLeftDP = funnel.sideLeftPlane(edge.leftVertex);
			Plane.PlaneSide leftPlaneRightDP = funnel.sideLeftPlane(edge.rightVertex);
			Plane.PlaneSide rightPlaneLeftDP = funnel.sideRightPlane(edge.leftVertex);
			Plane.PlaneSide rightPlaneRightDP = funnel.sideRightPlane(edge.rightVertex);

			if (rightPlaneRightDP != Plane.PlaneSide.Front) {
				if (leftPlaneRightDP != Plane.PlaneSide.Front) {
					// Tighten the funnel.
					funnel.setRightPlane(funnel.pivot, edge.rightVertex);
					rightIndex = i;
				} else {
					// Right over left, insert left to path and restart scan from portal left point.
					calculateEdgeCrossings(lastRestart, leftIndex, funnel.pivot, funnel.leftPortal);
					funnel.pivot.set(funnel.leftPortal);
					i = leftIndex;
					rightIndex = i;
					if (i < numEdges() - 1) {
						lastRestart = i;
						funnel.setPlanes(funnel.pivot, getEdge(i + 1));
						continue;
					}
					break;
				}
			}
			if (leftPlaneLeftDP != Plane.PlaneSide.Front) {
				if (rightPlaneLeftDP != Plane.PlaneSide.Front) {
					// Tighten the funnel.
					funnel.setLeftPlane(funnel.pivot, edge.leftVertex);
					leftIndex = i;
				} else {
					// Left over right, insert right to path and restart scan from portal right point.
					calculateEdgeCrossings(lastRestart, rightIndex, funnel.pivot, funnel.rightPortal);
					funnel.pivot.set(funnel.rightPortal);
					i = rightIndex;
					leftIndex = i;
					if (i < numEdges() - 1) {
						lastRestart = i;
						funnel.setPlanes(funnel.pivot, getEdge(i + 1));
						continue;
					}
					break;
				}
			}
		}
		calculateEdgeCrossings(lastRestart, numEdges() - 1, funnel.pivot, end);

		for (int i = 1; i < pathPoints.size; i++) {
			EdgePoint p = pathPoints.get(i);
			p.fromNode = pathPoints.get(i - 1).toNode;
		}
		return;
	}

	/**
	 * Store all edge crossing points between the start and end indices.
	 * If the path crosses exactly the start or end points (which is quite likely),
	 * store the edges in order of crossing in the EdgePoint data structure.
	 * <p/>
	 * Edge crossings are calculated as intersections with the plane from the
	 * start, end and up vectors.
	 *
	 * @param startIndex
	 * @param endIndex
	 * @param startPoint
	 * @param endPoint
	 */
	private void calculateEdgeCrossings(int startIndex, int endIndex,
										Vector3 startPoint, Vector3 endPoint) {

		if (startIndex >= numEdges() || endIndex >= numEdges()) {
			return;
		}
		crossingPlane.set(startPoint, tmp1.set(startPoint).add(up), endPoint);

		EdgePoint previousLast = lastPointAdded;

		Edge edge = getEdge(endIndex);
		EdgePoint end = new EdgePoint(new Vector3(endPoint), edge.toNode);

		for (int i = startIndex; i < endIndex; i++) {
			edge = getEdge(i);
			Vector3 xPoint = new Vector3();

			if (edge.rightVertex.equals(startPoint) || edge.leftVertex.equals(startPoint)) {
				previousLast.toNode = edge.toNode;
				if (!previousLast.connectingEdges.contains(edge, true)) {
					previousLast.connectingEdges.add(edge);
				}

			} else if (edge.leftVertex.equals(endPoint) || edge.rightVertex.equals(endPoint)) {
				if (!end.connectingEdges.contains(edge, true)) {
					end.connectingEdges.add(edge);
				}

			} else if (Intersector.intersectSegmentPlane(edge.leftVertex, edge.rightVertex, crossingPlane, xPoint)
					&& !Float.isNaN(xPoint.x) && !Float.isNaN(xPoint.y) && !Float.isNaN(xPoint.z)) {
				if (i != startIndex || i == 0) {
					lastPointAdded.toNode = edge.fromNode;
					EdgePoint crossing = new EdgePoint(xPoint, edge.toNode);
					crossing.connectingEdges.add(edge);
					addPoint(crossing);
				}
			}
		}
		if (endIndex < numEdges() - 1) {
			end.connectingEdges.add(getEdge(endIndex));
		}
		if (!lastPointAdded.equals(end)) {
			addPoint(end);
		}
	}

	/**
	 * From com.badlogic.gdx.ai.steer.paths.LinePath
	 * <p/>
	 * Returns the square distance of the nearest point on line segment {@code a-b}, from point {@code c}.
	 * Also, the {@code out}* vector is assigned to the nearest point.
	 *
	 * @param out the output vector that contains the nearest point on return
	 * @param a   the start point of the line segment
	 * @param b   the end point of the line segment
	 * @param c   the point to calculate the distance from
	 * @author davebaol
	 * @author Daniel Holderbaum
	 */
	private float calculatePointSegmentSquareDistance(Vector3 out, Vector3 a, Vector3 b, Vector3 c) {
		tmp1.set(a);
		tmp2.set(b);
		tmp3.set(c);

		Vector3 ab = tmp2.sub(a);
		float t = (tmp3.sub(a)).dot(ab) / ab.len2();
		t = MathUtils.clamp(t, 0, 1);
		out.set(tmp1.add(ab.scl(t)));

		tmp1.set(out);
		Vector3 distance = tmp1.sub(c);
		return distance.len2();
	}
}