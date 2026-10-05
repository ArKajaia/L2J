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
package org.l2jmobius.gameserver.managers;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

/**
 * The 16x12 crest of a clan run by fake players, in the format the client uploads a clan crest in (and the server sends it back): a DDS file with a 16x16 DXT1 texture (256 bytes), the 12 rows of the crest under 4 rows of padding.<br>
 * A crest is drawn from one of a few heraldic designs, or read from {@code data/fakeclans/<clan name>.bmp} (or {@code .png}, a 16x12 picture like the one a player uploads) or {@code .dds} (the crest data itself) if the operator put one there.
 */
public class FakeClanCrest
{
	private static final Logger LOGGER = Logger.getLogger(FakeClanCrest.class.getName());
	
	public static final int WIDTH = 16;
	public static final int HEIGHT = 12;
	
	/** The texture is 16x16: the first row of 4x4 blocks is padding, the crest is in the 3 rows under it. */
	private static final int TEXTURE_HEIGHT = 16;
	private static final int PADDING_ROWS = TEXTURE_HEIGHT - HEIGHT;
	private static final int HEADER_SIZE = 128;
	private static final int MAX_SIZE = 256;
	
	// Tinctures.
	private static final int GULES = 0xB81C1C;
	private static final int AZURE = 0x1F4FB0;
	private static final int VERT = 0x1E7F36;
	private static final int SABLE = 0x181818;
	private static final int PURPURE = 0x6E2A8C;
	private static final int OR = 0xE8C02A;
	private static final int ARGENT = 0xE4E4E4;
	private static final int ORANGE = 0xE06A1A;
	private static final int TEAL = 0x168C8C;
	private static final int BROWN = 0x6B4423;
	
	/** Field, charge and third color of each design, in the order of {@link #draw}. */
	private static final int[][] COLORS =
	{
		// @formatter:off
		{AZURE, OR, OR}, // Nordic cross.
		{SABLE, ARGENT, ARGENT}, // Quarterly.
		{PURPURE, SABLE, SABLE}, // Per bend.
		{GULES, OR, OR}, // Chevron.
		{SABLE, ARGENT, ARGENT}, // Saltire.
		{GULES, ORANGE, OR}, // Three bands.
		{SABLE, OR, OR}, // Lozenge in a bordure.
		{GULES, OR, OR}, // Pale and chief.
		{VERT, OR, OR}, // Checky.
		{TEAL, ARGENT, ARGENT}, // Pall.
		{BROWN, OR, OR}, // Fess.
		{AZURE, ARGENT, GULES}, // Per pale.
		// @formatter:on
	};
	
	private FakeClanCrest()
	{
	}
	
	/**
	 * @param clanName the clan name (for a crest the operator put in {@code data/fakeclans})
	 * @param index which design to draw when there is none, the clan's place in FakeClanNames
	 * @param datapackRoot the datapack folder
	 * @return the crest data, {@code null} if it couldn't be made
	 */
	public static byte[] create(String clanName, int index, File datapackRoot)
	{
		final byte[] custom = load(clanName, datapackRoot);
		return custom != null ? custom : encode(draw(index));
	}
	
	/**
	 * @param clanName the clan name
	 * @param datapackRoot the datapack folder
	 * @return the crest in {@code data/fakeclans/<clan name>.dds|.bmp|.png}, {@code null} if there is none (or it can't be read)
	 */
	private static byte[] load(String clanName, File datapackRoot)
	{
		final File folder = new File(datapackRoot, "data/fakeclans");
		final File dds = new File(folder, clanName + ".dds");
		try
		{
			if (dds.isFile())
			{
				final byte[] data = Files.readAllBytes(dds.toPath());
				if ((data.length > HEADER_SIZE) && (data.length <= MAX_SIZE) && (data[0] == 'D') && (data[1] == 'D') && (data[2] == 'S'))
				{
					return data;
				}
				LOGGER.warning(FakeClanCrest.class.getSimpleName() + ": " + dds + " is not a DDS crest of at most " + MAX_SIZE + " bytes, a crest is drawn instead.");
				return null;
			}
			
			for (String extension : new String[]
			{
				".bmp",
				".png"
			})
			{
				final File file = new File(folder, clanName + extension);
				if (!file.isFile())
				{
					continue;
				}
				
				final BufferedImage image = ImageIO.read(file);
				if (image == null)
				{
					LOGGER.warning(FakeClanCrest.class.getSimpleName() + ": Could not read " + file + ", a crest is drawn instead.");
					return null;
				}
				
				// Scaled to 16x12 if it isn't, like the client asks for.
				final int[] pixels = new int[WIDTH * HEIGHT];
				for (int y = 0; y < HEIGHT; y++)
				{
					for (int x = 0; x < WIDTH; x++)
					{
						pixels[(y * WIDTH) + x] = image.getRGB((x * image.getWidth()) / WIDTH, (y * image.getHeight()) / HEIGHT) & 0xFFFFFF;
					}
				}
				return encode(pixels);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, FakeClanCrest.class.getSimpleName() + ": Could not read the crest of " + clanName + ".", e);
		}
		return null;
	}
	
	/**
	 * @param index the design (wraps around)
	 * @return the 16x12 RGB pixels of the design
	 */
	static int[] draw(int index)
	{
		final int design = Math.floorMod(index, COLORS.length);
		final int field = COLORS[design][0];
		final int charge = COLORS[design][1];
		final int third = COLORS[design][2];
		final int[] pixels = new int[WIDTH * HEIGHT];
		for (int y = 0; y < HEIGHT; y++)
		{
			for (int x = 0; x < WIDTH; x++)
			{
				final int color;
				switch (design)
				{
					case 0: // Nordic cross, its upright left of the middle.
					{
						color = ((x >= 5) && (x <= 6)) || ((y >= 5) && (y <= 6)) ? charge : field;
						break;
					}
					case 1: // Quarterly.
					{
						color = (x < 8) == (y < 6) ? field : charge;
						break;
					}
					case 2: // Per bend: the lower left half.
					{
						color = (x * HEIGHT) < (y * WIDTH) ? charge : field;
						break;
					}
					case 3: // Chevron.
					{
						final int top = 3 + (Math.abs((2 * x) - 15) / 3);
						color = (y >= (11 - top)) && (y <= (14 - top)) ? charge : field;
						break;
					}
					case 4: // Saltire.
					{
						final int d1 = Math.abs((x * HEIGHT) - (y * WIDTH));
						final int d2 = Math.abs((x * HEIGHT) - ((HEIGHT - 1 - y) * WIDTH));
						color = (d1 <= 14) || (d2 <= 14) ? charge : field;
						break;
					}
					case 5: // Three bands, 4 rows each.
					{
						color = y < 4 ? field : y < 8 ? charge : third;
						break;
					}
					case 6: // A lozenge in a bordure of the same color.
					{
						final double dx = Math.abs(x - 7.5) / 4.5;
						final double dy = Math.abs(y - 5.5) / 4.0;
						color = (x == 0) || (x == (WIDTH - 1)) || (y == 0) || (y == (HEIGHT - 1)) || ((dx + dy) <= 1) ? charge : field;
						break;
					}
					case 7: // A pale and a chief.
					{
						color = (y < 3) || ((x >= 6) && (x <= 9)) ? charge : field;
						break;
					}
					case 8: // Checky, 4x4 squares.
					{
						color = (((x / 4) + (y / 4)) % 2) == 0 ? field : charge;
						break;
					}
					case 9: // Pall (a Y).
					{
						final boolean arms = (y <= 6) && ((Math.abs((x * 6) - (y * 8)) <= 9) || (Math.abs(((WIDTH - 1 - x) * 6) - (y * 8)) <= 9));
						final boolean stem = (y >= 5) && (x >= 6) && (x <= 9);
						color = arms || stem ? charge : field;
						break;
					}
					case 10: // Fess: a wide band across the middle.
					{
						color = (y >= 4) && (y <= 7) ? charge : field;
						break;
					}
					default: // Per pale, a roundel on the line.
					{
						final double dx = x - 7.5;
						final double dy = y - 5.5;
						color = ((dx * dx) + (dy * dy)) <= 9 ? third : x < 8 ? field : charge;
						break;
					}
				}
				pixels[(y * WIDTH) + x] = color;
			}
		}
		return pixels;
	}
	
	/**
	 * @param pixels the 16x12 RGB pixels of the crest
	 * @return the crest data: DDS header and DXT1 blocks, the first row of blocks repeating the top row of the crest
	 */
	static byte[] encode(int[] pixels)
	{
		final byte[] data = new byte[HEADER_SIZE + ((WIDTH / 4) * (TEXTURE_HEIGHT / 4) * 8)];
		writeHeader(data);
		
		int offset = HEADER_SIZE;
		final int[] block = new int[16];
		for (int blockY = 0; blockY < (TEXTURE_HEIGHT / 4); blockY++)
		{
			for (int blockX = 0; blockX < (WIDTH / 4); blockX++)
			{
				for (int i = 0; i < 16; i++)
				{
					final int textureY = (blockY * 4) + (i / 4);
					final int y = Math.max(0, textureY - PADDING_ROWS);
					block[i] = pixels[(y * WIDTH) + (blockX * 4) + (i % 4)];
				}
				writeBlock(data, offset, block);
				offset += 8;
			}
		}
		return data;
	}
	
	/**
	 * Writes a DXT1 block with only its two end colors used (the two most frequent colors of the block), so it shows the same whether a decoder reads it as a 3 or a 4 color block.
	 * @param data the data
	 * @param offset where the block goes
	 * @param block the 16 RGB pixels of the block, row by row
	 */
	private static void writeBlock(byte[] data, int offset, int[] block)
	{
		final Map<Integer, Integer> counts = new HashMap<>();
		for (int color : block)
		{
			counts.merge(toRgb565(color), 1, Integer::sum);
		}
		
		int first = -1;
		int second = -1;
		for (Map.Entry<Integer, Integer> entry : counts.entrySet())
		{
			if ((first < 0) || (entry.getValue() > counts.get(first)))
			{
				second = first;
				first = entry.getKey();
			}
			else if ((second < 0) || (entry.getValue() > counts.get(second)))
			{
				second = entry.getKey();
			}
		}
		if (second < 0)
		{
			second = first;
		}
		
		// color0 > color1: a 4 color block, of which only the two end colors (indices 0 and 1) are used.
		final int color0 = Math.max(first, second);
		final int color1 = Math.min(first, second);
		int indices = 0;
		for (int i = 0; i < 16; i++)
		{
			final int color = toRgb565(block[i]);
			final int index = (color0 != color1) && (distance(color, color1) < distance(color, color0)) ? 1 : 0;
			indices |= index << (i * 2);
		}
		
		writeShort(data, offset, color0);
		writeShort(data, offset + 2, color1);
		writeInt(data, offset + 4, indices);
	}
	
	private static int toRgb565(int rgb)
	{
		return (((rgb >> 19) & 0x1F) << 11) | (((rgb >> 10) & 0x3F) << 5) | ((rgb >> 3) & 0x1F);
	}
	
	private static int distance(int rgb565a, int rgb565b)
	{
		final int dr = ((rgb565a >> 11) & 0x1F) - ((rgb565b >> 11) & 0x1F);
		final int dg = (((rgb565a >> 5) & 0x3F) - ((rgb565b >> 5) & 0x3F)) / 2;
		final int db = (rgb565a & 0x1F) - (rgb565b & 0x1F);
		return (dr * dr) + (dg * dg) + (db * db);
	}
	
	private static void writeHeader(byte[] data)
	{
		data[0] = 'D';
		data[1] = 'D';
		data[2] = 'S';
		data[3] = ' ';
		writeInt(data, 4, 124); // Header size.
		writeInt(data, 8, 0x1 | 0x2 | 0x4 | 0x1000 | 0x80000); // Caps, height, width, pixel format, linear size.
		writeInt(data, 12, TEXTURE_HEIGHT);
		writeInt(data, 16, WIDTH);
		writeInt(data, 20, (WIDTH / 4) * (TEXTURE_HEIGHT / 4) * 8); // Linear size.
		writeInt(data, 76, 32); // Pixel format size.
		writeInt(data, 80, 0x4); // Four CC.
		data[84] = 'D';
		data[85] = 'X';
		data[86] = 'T';
		data[87] = '1';
		writeInt(data, 108, 0x1000); // Texture.
	}
	
	private static void writeShort(byte[] data, int offset, int value)
	{
		data[offset] = (byte) value;
		data[offset + 1] = (byte) (value >> 8);
	}
	
	private static void writeInt(byte[] data, int offset, int value)
	{
		data[offset] = (byte) value;
		data[offset + 1] = (byte) (value >> 8);
		data[offset + 2] = (byte) (value >> 16);
		data[offset + 3] = (byte) (value >> 24);
	}
}
