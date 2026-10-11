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
package org.l2jmobius.gameserver.geoengine.pathfinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.l2jmobius.gameserver.config.GeoEngineConfig;

/**
 * @author Mobius
 */
public class NodeBuffer
{
	private static final int MAX_ITERATIONS = 7000;
	
	private final ReentrantLock _lock = new ReentrantLock();
	private final int _mapSize;
	private final GeoNode[][] _buffer;
	
	// A* open list: a binary min-heap ordered by F cost, then H cost. Each node keeps its heap index, so finding and removing it need no scan of the list (see GeoNode).
	// The closed list is a flag on the node.
	private GeoNode[] _openList = new GeoNode[256];
	private int _openSize = 0;
	
	// The nodes this search put in use, so free() resets only those instead of the whole buffer.
	private final List<GeoNode> _usedNodes = new ArrayList<>();
	
	private int _baseX = 0;
	private int _baseY = 0;
	
	private int _targetX = 0;
	private int _targetY = 0;
	private int _targetZ = 0;
	
	private GeoNode _current = null;
	
	public NodeBuffer(int size)
	{
		_mapSize = size;
		_buffer = new GeoNode[_mapSize][_mapSize];
	}
	
	public final boolean lock()
	{
		return _lock.tryLock();
	}
	
	/**
	 * Enhanced A* pathfinding algorithm.
	 * @param x starting X coordinate
	 * @param y starting Y coordinate
	 * @param z starting Z coordinate
	 * @param tx target X coordinate
	 * @param ty target Y coordinate
	 * @param tz target Z coordinate
	 * @return the final node if path found, null otherwise
	 */
	public GeoNode findPath(int x, int y, int z, int tx, int ty, int tz)
	{
		_baseX = x + ((tx - x - _mapSize) / 2); // Middle of the line (x,y) - (tx,ty).
		_baseY = y + ((ty - y - _mapSize) / 2); // Will be in the center of the buffer.
		_targetX = tx;
		_targetY = ty;
		_targetZ = tz;
		
		_current = getNode(x, y, z);
		if (_current == null)
		{
			return null;
		}
		
		// Initialize start node.
		_current.setGCost(0);
		_current.setHCost(getCost(x, y, z));
		_current.calculateFCost();
		
		openListAdd(_current);
		
		for (int count = 0; count < MAX_ITERATIONS; count++)
		{
			if (_openSize == 0)
			{
				return null; // No path found.
			}
			
			_current = openListPoll();
			
			// Check if we reached the target.
			if ((_current.getLocation().getNodeX() == _targetX) && (_current.getLocation().getNodeY() == _targetY) && (Math.abs(_current.getLocation().getZ() - _targetZ) < 64))
			{
				return _current; // Found target.
			}
			
			_current.setClosed();
			
			// Get and process neighbors.
			getNeighbors();
		}
		
		return null; // Path not found within iteration limit.
	}
	
	public void free()
	{
		_current = null;
		Arrays.fill(_openList, 0, _openSize, null);
		_openSize = 0;
		
		for (int i = 0; i < _usedNodes.size(); i++)
		{
			_usedNodes.get(i).free();
		}
		_usedNodes.clear();
		
		_lock.unlock();
	}
	
	/**
	 * Enhanced neighbor discovery using A* principles.
	 */
	private void getNeighbors()
	{
		if (_current.getLocation().canGoNone())
		{
			return;
		}
		
		final int x = _current.getLocation().getNodeX();
		final int y = _current.getLocation().getNodeY();
		final int z = _current.getLocation().getZ();
		
		GeoNode nodeE = null;
		GeoNode nodeS = null;
		GeoNode nodeW = null;
		GeoNode nodeN = null;
		
		// East.
		if (_current.getLocation().canGoEast())
		{
			nodeE = addNode(x + 1, y, z, false);
		}
		
		// South.
		if (_current.getLocation().canGoSouth())
		{
			nodeS = addNode(x, y + 1, z, false);
		}
		
		// West.
		if (_current.getLocation().canGoWest())
		{
			nodeW = addNode(x - 1, y, z, false);
		}
		
		// North.
		if (_current.getLocation().canGoNorth())
		{
			nodeN = addNode(x, y - 1, z, false);
		}
		
		// Diagonal movements (if enabled).
		if (!GeoEngineConfig.ADVANCED_DIAGONAL_STRATEGY)
		{
			return;
		}
		
		// SouthEast
		if ((nodeE != null) && (nodeS != null) && nodeE.getLocation().canGoSouth() && nodeS.getLocation().canGoEast())
		{
			addNode(x + 1, y + 1, z, true);
		}
		
		// SouthWest
		if ((nodeS != null) && (nodeW != null) && nodeW.getLocation().canGoSouth() && nodeS.getLocation().canGoWest())
		{
			addNode(x - 1, y + 1, z, true);
		}
		
		// NorthEast
		if ((nodeN != null) && (nodeE != null) && nodeE.getLocation().canGoNorth() && nodeN.getLocation().canGoEast())
		{
			addNode(x + 1, y - 1, z, true);
		}
		
		// NorthWest
		if ((nodeN != null) && (nodeW != null) && nodeW.getLocation().canGoNorth() && nodeN.getLocation().canGoWest())
		{
			addNode(x - 1, y - 1, z, true);
		}
	}
	
	private GeoNode getNode(int x, int y, int z)
	{
		final int aX = x - _baseX;
		if ((aX < 0) || (aX >= _mapSize))
		{
			return null;
		}
		
		final int aY = y - _baseY;
		if ((aY < 0) || (aY >= _mapSize))
		{
			return null;
		}
		
		GeoNode result = _buffer[aX][aY];
		if (result == null)
		{
			result = new GeoNode(new GeoLocation(x, y, z));
			_buffer[aX][aY] = result;
			_usedNodes.add(result);
		}
		else if (!result.isInUse())
		{
			result.setInUse();
			_usedNodes.add(result);
			
			// Re-init node if needed.
			if (result.getLocation() != null)
			{
				result.getLocation().set(x, y, z);
			}
			else
			{
				result.setLoc(new GeoLocation(x, y, z));
			}
			
			// Reset A* costs and clear parent reference.
			result.resetCosts();
			result.setParent(null);
		}
		
		return result;
	}
	
	/**
	 * Enhanced node addition using A* algorithm.
	 * @param x the X coordinate
	 * @param y the Y coordinate
	 * @param z the Z coordinate
	 * @param diagonal whether this is a diagonal move
	 * @return the added node or null if not valid
	 */
	private GeoNode addNode(int x, int y, int z, boolean diagonal)
	{
		final GeoNode newNode = getNode(x, y, z);
		if (newNode == null)
		{
			return null;
		}
		
		// Skip if already in closed list.
		if (newNode.isClosed())
		{
			return newNode;
		}
		
		final int geoZ = newNode.getLocation().getZ();
		final int stepZ = Math.abs(geoZ - _current.getLocation().getZ());
		
		// Calculate movement cost based on terrain and movement type.
		float weight = diagonal ? GeoEngineConfig.DIAGONAL_WEIGHT : GeoEngineConfig.LOW_WEIGHT;
		if (!newNode.getLocation().canGoAll() || (stepZ > 16))
		{
			weight = GeoEngineConfig.HIGH_WEIGHT;
		}
		else if (isHighWeight(x + 1, y, geoZ) || isHighWeight(x - 1, y, geoZ) || isHighWeight(x, y + 1, geoZ) || isHighWeight(x, y - 1, geoZ))
		{
			weight = GeoEngineConfig.MEDIUM_WEIGHT;
		}
		
		// Calculate new G cost (actual cost from start).
		final double newGCost = _current.getGCost() + weight;
		
		// Check if this node is already in open list.
		final boolean inOpenList = newNode.getHeapIndex() >= 0;
		
		// If not in open list or we found a better path.
		if (!inOpenList || (newGCost < newNode.getGCost()))
		{
			// Set parent and costs.
			newNode.setParent(_current);
			newNode.setGCost(newGCost);
			newNode.setHCost(getCost(x, y, geoZ));
			newNode.calculateFCost();
			
			if (!inOpenList)
			{
				openListAdd(newNode);
			}
			else
			{
				// Its cost went down: take it out and put it back, which keeps the order of equal cost nodes (and so the paths) as with the PriorityQueue used before.
				openListRemove(newNode);
				openListAdd(newNode);
			}
		}
		
		return newNode;
	}
	
	private void openListAdd(GeoNode node)
	{
		if (_openSize == _openList.length)
		{
			_openList = Arrays.copyOf(_openList, _openSize * 2);
		}
		
		_openList[_openSize] = node;
		node.setHeapIndex(_openSize);
		siftUp(_openSize++);
	}
	
	private GeoNode openListPoll()
	{
		final GeoNode first = _openList[0];
		first.setHeapIndex(-1);
		final GeoNode last = _openList[--_openSize];
		_openList[_openSize] = null;
		if (_openSize > 0)
		{
			_openList[0] = last;
			last.setHeapIndex(0);
			siftDown(0);
		}
		
		return first;
	}
	
	private void openListRemove(GeoNode node)
	{
		final int index = node.getHeapIndex();
		node.setHeapIndex(-1);
		final int lastIndex = --_openSize;
		if (lastIndex == index)
		{
			_openList[index] = null;
			return;
		}
		
		final GeoNode moved = _openList[lastIndex];
		_openList[lastIndex] = null;
		_openList[index] = moved;
		moved.setHeapIndex(index);
		siftDown(index);
		if (_openList[index] == moved)
		{
			siftUp(index);
		}
	}
	
	private void siftUp(int index)
	{
		final GeoNode node = _openList[index];
		int i = index;
		while (i > 0)
		{
			final int parentIndex = (i - 1) >>> 1;
			final GeoNode parent = _openList[parentIndex];
			if (!isCheaper(node, parent))
			{
				break;
			}
			
			_openList[i] = parent;
			parent.setHeapIndex(i);
			i = parentIndex;
		}
		
		_openList[i] = node;
		node.setHeapIndex(i);
	}
	
	private void siftDown(int index)
	{
		final GeoNode node = _openList[index];
		int i = index;
		final int half = _openSize >>> 1;
		while (i < half)
		{
			int childIndex = (i << 1) + 1;
			GeoNode child = _openList[childIndex];
			final int rightIndex = childIndex + 1;
			if ((rightIndex < _openSize) && isCheaper(_openList[rightIndex], child))
			{
				childIndex = rightIndex;
				child = _openList[childIndex];
			}
			
			if (!isCheaper(child, node))
			{
				break;
			}
			
			_openList[i] = child;
			child.setHeapIndex(i);
			i = childIndex;
		}
		
		_openList[i] = node;
		node.setHeapIndex(i);
	}
	
	/**
	 * @param a a node
	 * @param b another node
	 * @return {@code true} if {@code a} comes first in the open list: lower F cost, or the same F cost and a lower H cost
	 */
	private static boolean isCheaper(GeoNode a, GeoNode b)
	{
		if (a.getFCost() != b.getFCost())
		{
			return a.getFCost() < b.getFCost();
		}
		
		return a.getHCost() < b.getHCost();
	}
	
	private boolean isHighWeight(int x, int y, int z)
	{
		final GeoNode result = getNode(x, y, z);
		return (result == null) || !result.getLocation().canGoAll() || (Math.abs(result.getLocation().getZ() - z) > 16);
	}
	
	/**
	 * Calculate heuristic cost using 3D distance with proper scaling.
	 * @param x the X coordinate
	 * @param y the Y coordinate
	 * @param z the Z coordinate
	 * @return the heuristic cost
	 */
	private double getCost(int x, int y, int z)
	{
		final int dX = x - _targetX;
		final int dY = y - _targetY;
		final int dZ = z - _targetZ;
		
		// Use 3D Euclidean distance for more accurate heuristic.
		return Math.sqrt((dX * dX) + (dY * dY) + ((dZ * dZ) / 256.0));
	}
}
