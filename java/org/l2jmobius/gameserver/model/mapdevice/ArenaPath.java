/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.model.mapdevice;

import java.util.ArrayList;
import java.util.List;

/**
 * A field shaped like a path: a line of waypoints, each with the height of the ground and the half-width of the way there, that the fence follows on both sides (round at both
 * ends). A long, narrow valley or road can bend with the ground instead of being cut out of an ellipse. The players come in at its first waypoint.
 */
public final class ArenaPath
{
	/**
	 * A waypoint.
	 * @param x where it is
	 * @param y where it is
	 * @param z the height of the ground there
	 * @param width how far the field reaches on each side of the path there (half its width)
	 */
	public record Waypoint(int x, int y, int z, int width)
	{
	}

	/**
	 * A spot on the path.
	 * @param x where it is
	 * @param y where it is
	 * @param z the height of the ground there
	 * @param dirX the way the path goes there (a unit vector)
	 * @param dirY the way the path goes there (a unit vector)
	 * @param width the half-width of the field there
	 */
	public record Spot(int x, int y, int z, double dirX, double dirY, int width)
	{
	}

	/** Points on each round end of the outline, between its two sides. */
	private static final int CAP_POINTS = 5;
	/** A bend's outer corner reaches at most this many half-widths from the path. */
	private static final double MITER_LIMIT = 2.0;

	private final List<Waypoint> _points;
	private final double[] _segmentLengths;
	private final double _length;
	private final int[] _outlineX;
	private final int[] _outlineY;
	private final int _minX;
	private final int _minY;
	private final int _maxX;
	private final int _maxY;
	private final int _maxWidth;

	/**
	 * @param points the waypoints, at least two, from where the players come in to the far end
	 * @throws IllegalArgumentException with fewer than two waypoints, or two in the same spot
	 */
	public ArenaPath(List<Waypoint> points)
	{
		if (points.size() < 2)
		{
			throw new IllegalArgumentException("a path needs two waypoints at least");
		}
		_points = List.copyOf(points);
		_segmentLengths = new double[_points.size() - 1];
		double length = 0;
		int maxWidth = 0;
		for (int i = 0; i < _segmentLengths.length; i++)
		{
			final Waypoint a = _points.get(i);
			final Waypoint b = _points.get(i + 1);
			_segmentLengths[i] = Math.hypot(b.x() - a.x(), b.y() - a.y());
			if (_segmentLengths[i] < 1)
			{
				throw new IllegalArgumentException("two waypoints in the same spot");
			}
			length += _segmentLengths[i];
		}
		for (Waypoint point : _points)
		{
			maxWidth = Math.max(maxWidth, point.width());
		}
		_length = length;
		_maxWidth = maxWidth;

		final List<int[]> outline = buildOutline();
		_outlineX = new int[outline.size()];
		_outlineY = new int[outline.size()];
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int i = 0; i < outline.size(); i++)
		{
			_outlineX[i] = outline.get(i)[0];
			_outlineY[i] = outline.get(i)[1];
			minX = Math.min(minX, _outlineX[i]);
			minY = Math.min(minY, _outlineY[i]);
			maxX = Math.max(maxX, _outlineX[i]);
			maxY = Math.max(maxY, _outlineY[i]);
		}
		_minX = minX;
		_minY = minY;
		_maxX = maxX;
		_maxY = maxY;
	}

	/**
	 * The outline: the left side from the first waypoint to the last, round the far end, the right side back, and round the near end. At a bend each side is moved out along the
	 * mean of the two segments' normals, far enough that the way keeps its width (at most {@link #MITER_LIMIT} half-widths).
	 */
	private List<int[]> buildOutline()
	{
		final int n = _points.size();
		final double[] normalX = new double[n - 1];
		final double[] normalY = new double[n - 1];
		for (int i = 0; i < (n - 1); i++)
		{
			final Waypoint a = _points.get(i);
			final Waypoint b = _points.get(i + 1);
			normalX[i] = -(b.y() - a.y()) / _segmentLengths[i];
			normalY[i] = (b.x() - a.x()) / _segmentLengths[i];
		}
		final double[] offsetX = new double[n];
		final double[] offsetY = new double[n];
		for (int i = 0; i < n; i++)
		{
			final double width = _points.get(i).width();
			if ((i == 0) || (i == (n - 1)))
			{
				final int segment = i == 0 ? 0 : n - 2;
				offsetX[i] = normalX[segment] * width;
				offsetY[i] = normalY[segment] * width;
				continue;
			}
			double mx = normalX[i - 1] + normalX[i];
			double my = normalY[i - 1] + normalY[i];
			final double ml = Math.hypot(mx, my);
			if (ml < 1e-9)
			{
				mx = normalX[i];
				my = normalY[i];
			}
			else
			{
				mx /= ml;
				my /= ml;
			}
			final double scale = width / Math.max(1 / MITER_LIMIT, (mx * normalX[i]) + (my * normalY[i]));
			offsetX[i] = mx * scale;
			offsetY[i] = my * scale;
		}

		final List<int[]> outline = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			outline.add(point(_points.get(i).x() + offsetX[i], _points.get(i).y() + offsetY[i]));
		}
		addCap(outline, _points.get(n - 1), Math.atan2(-normalX[n - 2], normalY[n - 2]));
		for (int i = n - 1; i >= 0; i--)
		{
			outline.add(point(_points.get(i).x() - offsetX[i], _points.get(i).y() - offsetY[i]));
		}
		addCap(outline, _points.get(0), Math.atan2(normalX[0], -normalY[0]));
		return outline;
	}

	/**
	 * A round end: half a circle of the waypoint's width round it, from one side of the path to the other.
	 * @param heading the way out of the field at that end, in radians
	 */
	private static void addCap(List<int[]> outline, Waypoint end, double heading)
	{
		for (int k = 1; k <= CAP_POINTS; k++)
		{
			final double angle = (heading + (Math.PI / 2)) - ((k * Math.PI) / (CAP_POINTS + 1));
			outline.add(point(end.x() + (Math.cos(angle) * end.width()), end.y() + (Math.sin(angle) * end.width())));
		}
	}

	private static int[] point(double x, double y)
	{
		return new int[]
		{
			(int) Math.round(x),
			(int) Math.round(y)
		};
	}

	/**
	 * @return the waypoints, from where the players come in
	 */
	public List<Waypoint> getPoints()
	{
		return _points;
	}

	/**
	 * @return how long the path is, from the first waypoint to the last
	 */
	public double getLength()
	{
		return _length;
	}

	/**
	 * @return the widest half-width of the field
	 */
	public int getMaxWidth()
	{
		return _maxWidth;
	}

	/**
	 * @return the corners of the fence, in order round the field
	 */
	public int[][] getOutline()
	{
		final int[][] outline = new int[_outlineX.length][];
		for (int i = 0; i < _outlineX.length; i++)
		{
			outline[i] = new int[]
			{
				_outlineX[i],
				_outlineY[i]
			};
		}
		return outline;
	}

	/**
	 * @return the middle of the box round the fence, x
	 */
	public int getCenterX()
	{
		return (_minX + _maxX) / 2;
	}

	/**
	 * @return the middle of the box round the fence, y
	 */
	public int getCenterY()
	{
		return (_minY + _maxY) / 2;
	}

	/**
	 * @return half the width of the box round the fence
	 */
	public int getRadiusX()
	{
		return (_maxX - _minX) / 2;
	}

	/**
	 * @return half the height of the box round the fence
	 */
	public int getRadiusY()
	{
		return (_maxY - _minY) / 2;
	}

	/**
	 * @param distance how far along the path, from its first waypoint (clamped to the path)
	 * @return the spot of the path there, with its height, direction and half-width
	 */
	public Spot at(double distance)
	{
		double left = Math.max(0, Math.min(_length, distance));
		int segment = 0;
		while ((segment < (_segmentLengths.length - 1)) && (left > _segmentLengths[segment]))
		{
			left -= _segmentLengths[segment];
			segment++;
		}
		final Waypoint a = _points.get(segment);
		final Waypoint b = _points.get(segment + 1);
		final double t = Math.min(1, left / _segmentLengths[segment]);
		return new Spot((int) Math.round(a.x() + ((b.x() - a.x()) * t)), (int) Math.round(a.y() + ((b.y() - a.y()) * t)), (int) Math.round(a.z() + ((b.z() - a.z()) * t)), (b.x() - a.x()) / _segmentLengths[segment], (b.y() - a.y()) / _segmentLengths[segment], (int) Math.round(a.width() + ((b.width() - a.width()) * t)));
	}

	/**
	 * @param px a spot's x
	 * @param py a spot's y
	 * @return how far along the path its nearest point lies
	 */
	public double nearestDistance(int px, int py)
	{
		double best = Double.MAX_VALUE;
		double bestAlong = 0;
		double start = 0;
		for (int i = 0; i < _segmentLengths.length; i++)
		{
			final Waypoint a = _points.get(i);
			final Waypoint b = _points.get(i + 1);
			final double dx = (b.x() - a.x()) / _segmentLengths[i];
			final double dy = (b.y() - a.y()) / _segmentLengths[i];
			final double along = Math.max(0, Math.min(_segmentLengths[i], ((px - a.x()) * dx) + ((py - a.y()) * dy)));
			final double distance = Math.hypot((a.x() + (dx * along)) - px, (a.y() + (dy * along)) - py);
			if (distance < best)
			{
				best = distance;
				bestAlong = start + along;
			}
			start += _segmentLengths[i];
		}
		return bestAlong;
	}

	/**
	 * @param px a spot's x
	 * @param py a spot's y
	 * @param margin how far beyond the fence still counts as inside
	 * @return {@code true} if the spot lies inside the fence (or within {@code margin} beyond it)
	 */
	public boolean contains(int px, int py, int margin)
	{
		if ((px < (_minX - margin)) || (px > (_maxX + margin)) || (py < (_minY - margin)) || (py > (_maxY + margin)))
		{
			return false;
		}
		// Even-odd ray cast, then the distance to the fence for the margin.
		boolean inside = false;
		double nearest = Double.MAX_VALUE;
		for (int i = 0, j = _outlineX.length - 1; i < _outlineX.length; j = i++)
		{
			final int xi = _outlineX[i];
			final int yi = _outlineY[i];
			final int xj = _outlineX[j];
			final int yj = _outlineY[j];
			if (((yi > py) != (yj > py)) && (px < ((((double) (xj - xi) * (py - yi)) / (yj - yi)) + xi)))
			{
				inside = !inside;
			}
			if (margin > 0)
			{
				final double ex = xj - xi;
				final double ey = yj - yi;
				final double lengthSq = (ex * ex) + (ey * ey);
				final double t = lengthSq <= 0 ? 0 : Math.max(0, Math.min(1, (((px - xi) * ex) + ((py - yi) * ey)) / lengthSq));
				nearest = Math.min(nearest, Math.hypot((xi + (ex * t)) - px, (yi + (ey * t)) - py));
			}
		}
		return inside || (nearest <= margin);
	}
}
