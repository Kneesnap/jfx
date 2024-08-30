/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.prism.impl;

import com.sun.javafx.geom.Quat4f;
import com.sun.javafx.geom.Vec2f;
import com.sun.javafx.geom.Vec3f;
import com.sun.javafx.geom.Vec4f;
import com.sun.prism.Mesh;
import java.util.Arrays;
import java.util.HashMap;
import javafx.scene.shape.VertexFormat;
import com.sun.javafx.logging.PlatformLogger;

/**
 * TODO: 3D - Need documentation
 */
public abstract class BaseMesh extends BaseGraphicsResource implements Mesh {

    private int nVerts;
    private int nTVerts;
    private int nFaces;
    private float[] pos;
    private float[] uv;
    private float[] colors;
    private int[] faces;
    private int[] smoothing;
    private boolean allSameSmoothing;
    private boolean allHardEdges;

    protected static final int POINT_SIZE = 3;
    protected static final int NORMAL_SIZE = 3;
    protected static final int TEXCOORD_SIZE = 2;
    protected static final int COLOR_SIZE = 4;

    protected static final int POINT_SIZE_VB = 3;
    protected static final int TEXCOORD_SIZE_VB = 2;
    protected static final int NORMAL_SIZE_VB = 4;
    protected static final int COLOR_SIZE_VB = 4;
    //point (3 floats), texcoord (2 floats), normal (as in 4 floats), and color (4 floats)
    protected static final int VERTEX_SIZE_VB = POINT_SIZE_VB + TEXCOORD_SIZE_VB
            + NORMAL_SIZE_VB + COLOR_SIZE_VB;

    private static final int VERTICES_PER_FACE = 3;

    // The following are used for obtaining face data from faces which do not contain normal data.
    // Data members container for a single face
    //    Vec3i pVerts;
    //    Vec3i tVerts;
    //    int  smGroup;
    public static enum FaceMembers {
        POINT0, TEXCOORD0, COLOR0, POINT1, TEXCOORD1, COLOR1, POINT2, TEXCOORD2, COLOR2, SMOOTHING_GROUP
    }
    public static final int FACE_MEMBERS_SIZE = FaceMembers.values().length;
    public static final int FACE_MEMBERS_COPIED_SIZE = FACE_MEMBERS_SIZE - 1;

    protected BaseMesh(Disposer.Record disposerRecord) {
        super(disposerRecord);
    }

    public abstract boolean buildNativeGeometry(float[] vertexBuffer,
            int vertexBufferLength, int[] indexBufferInt, int indexBufferLength);

    public abstract boolean buildNativeGeometry(float[] vertexBuffer,
            int vertexBufferLength, short[] indexBufferShort, int indexBufferLength);

    private boolean[] dirtyVertices;
    private float[] cachedNormals;
    private float[] cachedTangents;
    private float[] cachedBitangents;
    private float[] vertexBuffer;
    private int[] indexBuffer;
    private short[] indexBufferShort;
    private int indexBufferSize;
    private int numberOfVertices;

    private HashMap<Integer, MeshGeomComp2VB> point2vbMap;
    private HashMap<Integer, MeshGeomComp2VB> normal2vbMap;
    private HashMap<Integer, MeshGeomComp2VB> texCoord2vbMap;
    private HashMap<Integer, MeshGeomComp2VB> color2vbMap;

    private void convertNormalsToQuats(MeshTempState instance, int numberOfVertices,
            float[] normals, float[] tangents, float[] bitangents,
            float[] vertexBuffer, boolean[] dirtys) {

        Vec3f normal = instance.vec3f1;
        Vec3f tangent = instance.vec3f2;
        Vec3f bitangent = instance.vec3f3;
        for (int i = 0, vbIndex = 0; i < numberOfVertices; i++, vbIndex += VERTEX_SIZE_VB) {
            // Note: If dirtys isn't null, dirtys.length == numberOfVertices is true
            if (dirtys == null || dirtys[i]) {
                int index = i * NORMAL_SIZE;

                normal.x = normals[index];
                normal.y = normals[index + 1];
                normal.z = normals[index + 2];
                normal.normalize();

                // tangent and bitangent have been normalized.
                tangent.x = tangents[index];
                tangent.y = tangents[index + 1];
                tangent.z = tangents[index + 2];
                bitangent.x = bitangents[index];
                bitangent.y = bitangents[index + 1];
                bitangent.z = bitangents[index + 2];

                instance.triNormals[0].set(normal);
                instance.triNormals[1].set(tangent);
                instance.triNormals[2].set(bitangent);
                MeshUtil.fixTSpace(instance.triNormals);
                buildVSQuat(instance.triNormals, instance.quat);

                vertexBuffer[vbIndex + 5] = instance.quat.x;
                vertexBuffer[vbIndex + 6] = instance.quat.y;
                vertexBuffer[vbIndex + 7] = instance.quat.z;
                vertexBuffer[vbIndex + 8] = instance.quat.w;
            }
        }
    }

    // Build PointNormalTexCoordGeometry
    private boolean doBuildPNTCGeometry(VertexFormat vertexFormat, float[] points, float[] normals,
            float[] texCoords, float[] colors, int[] faces) {

        if (point2vbMap == null) {
            point2vbMap = new HashMap();
        } else {
            point2vbMap.clear();
        }
        if (normal2vbMap == null) {
            normal2vbMap = new HashMap();
        } else {
            normal2vbMap.clear();
        }
        if (texCoord2vbMap == null) {
            texCoord2vbMap = new HashMap();
        } else {
            texCoord2vbMap.clear();
        }
        if (color2vbMap == null) {
            color2vbMap = new HashMap();
        } else {
            color2vbMap.clear();
        }

        int vertexIndexSize = vertexFormat.getVertexIndexSize();
        int faceIndexSize = vertexIndexSize * VERTICES_PER_FACE;
        int pointIndexOffset = vertexFormat.getPointIndexOffset();
        int normalIndexOffset = vertexFormat.getNormalIndexOffset();
        int texCoordIndexOffset = vertexFormat.getTexCoordIndexOffset();
        int colorIndexOffset = vertexFormat.getColorIndexOffset();
        boolean hasColors = vertexFormat.getColorIndexOffset() >= 0;

        int numPoints = points.length / POINT_SIZE;
        int numNormals = normals.length / NORMAL_SIZE;
        int numTexCoords = texCoords.length / TEXCOORD_SIZE;
        int numFaces = faces.length / faceIndexSize;
        assert numPoints > 0 && numNormals > 0 && numTexCoords > 0 && numFaces > 0;

        Integer mf2vb;
        BaseMesh.MeshGeomComp2VB mp2vb;
        BaseMesh.MeshGeomComp2VB mn2vb;
        BaseMesh.MeshGeomComp2VB mt2vb;
        BaseMesh.MeshGeomComp2VB mc2vb;
        // Allocate an initial size, may grow as we process the faces array.
        cachedNormals = new float[numPoints * NORMAL_SIZE];
        cachedTangents =  new float[numPoints * NORMAL_SIZE];
        cachedBitangents = new float[numPoints * NORMAL_SIZE];
        vertexBuffer = new float[numPoints * VERTEX_SIZE_VB];
        indexBuffer = new int[numFaces * VERTICES_PER_FACE];
        int ibCount = 0;
        int vbCount = 0;

        MeshTempState instance = MeshTempState.getInstance();
        for (int i = 0; i < VERTICES_PER_FACE; i++) {
            if (instance.triPoints[i] == null) {
                instance.triPoints[i] = new Vec3f();
            }
            if (instance.triTexCoords[i] == null) {
                instance.triTexCoords[i] = new Vec2f();
            }
        }

        for (int faceCount = 0; faceCount < numFaces; faceCount++) {
            int faceIndex = faceCount * faceIndexSize;
            for (int i = 0; i < VERTICES_PER_FACE; i++) {
                int vertexIndex = faceIndex + (i * vertexIndexSize);
                int pointIndex = vertexIndex + pointIndexOffset;
                int normalIndex = vertexIndex + normalIndexOffset;
                int texCoordIndex = vertexIndex + texCoordIndexOffset;
                int colorIndex = vertexIndex + colorIndexOffset;

                mf2vb = vbCount / VERTEX_SIZE_VB;

                if (vertexBuffer.length <= vbCount) {
                    int numVertices = vbCount / VERTEX_SIZE_VB;
                    // Increment by 1/8th of numVertices or 6 (by 2 triangles) which ever is greater
                    final int newNumVertices = numVertices + Math.max((numVertices >> 3), 2 * VERTICES_PER_FACE);
                    float[] temp = new float[newNumVertices * VERTEX_SIZE_VB];
                    System.arraycopy(vertexBuffer, 0, temp, 0, vertexBuffer.length);
                    vertexBuffer = temp;
                    // Enlarge cachedNormals, cachedTangents and cachedBitangents too
                    temp = new float[newNumVertices * 3];
                    System.arraycopy(cachedNormals, 0, temp, 0, cachedNormals.length);
                    cachedNormals = temp;
                    temp = new float[newNumVertices * 3];
                    System.arraycopy(cachedTangents, 0, temp, 0, cachedTangents.length);
                    cachedTangents = temp;
                    temp = new float[newNumVertices * 3];
                    System.arraycopy(cachedBitangents, 0, temp, 0, cachedBitangents.length);
                    cachedBitangents = temp;
                }
                int pointOffset = faces[pointIndex] * POINT_SIZE;
                int normalOffset = faces[normalIndex] * NORMAL_SIZE;
                int texCoordOffset = faces[texCoordIndex] * TEXCOORD_SIZE;
                int colorOffset = hasColors ? faces[colorIndex] * COLOR_SIZE : -1;

                // Save the vertex of triangle
                instance.triPointIndex[i] = pointOffset;
                instance.triTexCoordIndex[i] = texCoordOffset;
                instance.triVerts[i] = vbCount / VERTEX_SIZE_VB;

                vertexBuffer[vbCount] = points[pointOffset];
                vertexBuffer[vbCount + 1] = points[pointOffset + 1];
                vertexBuffer[vbCount + 2] = points[pointOffset + 2];
                vertexBuffer[vbCount + POINT_SIZE_VB] = texCoords[texCoordOffset];
                vertexBuffer[vbCount + POINT_SIZE_VB + 1] = texCoords[texCoordOffset + 1];
                writeColorsToBuffer(colors, colorOffset, vertexBuffer,
                        vbCount + POINT_SIZE_VB + TEXCOORD_SIZE_VB + NORMAL_SIZE_VB);

                // Store the normal in the cachedNormals array
                int index = instance.triVerts[i] * NORMAL_SIZE;
                cachedNormals[index] = normals[normalOffset];
                cachedNormals[index + 1] = normals[normalOffset + 1];
                cachedNormals[index + 2] = normals[normalOffset + 2];

                vbCount += VERTEX_SIZE_VB;

                mp2vb = point2vbMap.get(pointOffset);
                if (mp2vb == null) {
                    // create
                    mp2vb = new MeshGeomComp2VB(pointOffset, mf2vb);
                    point2vbMap.put(pointOffset, mp2vb);
                } else {
                    // addLoc
                    mp2vb.addLoc(mf2vb);
                }

                mn2vb = normal2vbMap.get(normalOffset);
                if (mn2vb == null) {
                    // create
                    mn2vb = new MeshGeomComp2VB(normalOffset, mf2vb);
                    normal2vbMap.put(normalOffset, mn2vb);
                } else {
                    // addLoc
                    mn2vb.addLoc(mf2vb);
                }

                mt2vb = texCoord2vbMap.get(texCoordOffset);
                if (mt2vb == null) {
                    // create
                    mt2vb = new MeshGeomComp2VB(texCoordOffset, mf2vb);
                    texCoord2vbMap.put(texCoordOffset, mt2vb);
                } else {
                    // addLoc
                    mt2vb.addLoc(mf2vb);
                }

                if (colorOffset >= 0) {
                    mc2vb = color2vbMap.get(colorOffset);
                    if (mc2vb == null) {
                        // create
                        mc2vb = new MeshGeomComp2VB(colorOffset, mf2vb);
                        color2vbMap.put(colorOffset, mc2vb);
                    } else {
                        // addLoc
                        mc2vb.addLoc(mf2vb);
                    }
                }

                // Construct IndexBuffer
                indexBuffer[ibCount++] = mf2vb;
            }

            // This is the best time to compute the tangent and bitangent for each
            // of the vertex. Go thro. the 3 vertices of a triangle
            for (int i = 0; i < VERTICES_PER_FACE; i++) {
                instance.triPoints[i].x = points[instance.triPointIndex[i]];
                instance.triPoints[i].y = points[instance.triPointIndex[i] + 1];
                instance.triPoints[i].z = points[instance.triPointIndex[i] + 2];
                instance.triTexCoords[i].x = texCoords[instance.triTexCoordIndex[i]];
                instance.triTexCoords[i].y = texCoords[instance.triTexCoordIndex[i] + 1];
            }

            MeshUtil.computeTBNNormalized(instance.triPoints[0], instance.triPoints[1],
                    instance.triPoints[2], instance.triTexCoords[0],
                    instance.triTexCoords[1], instance.triTexCoords[2],
                    instance.triNormals);

            for (int i = 0; i < VERTICES_PER_FACE; i++) {
                int index = instance.triVerts[i] * NORMAL_SIZE;
                cachedTangents[index] = instance.triNormals[1].x;
                cachedTangents[index + 1] = instance.triNormals[1].y;
                cachedTangents[index + 2] = instance.triNormals[1].z;
                cachedBitangents[index] = instance.triNormals[2].x;
                cachedBitangents[index + 1] = instance.triNormals[2].y;
                cachedBitangents[index + 2] = instance.triNormals[2].z;
            }

        }

        numberOfVertices = vbCount / VERTEX_SIZE_VB;

        convertNormalsToQuats(instance, numberOfVertices,
                cachedNormals, cachedTangents, cachedBitangents, vertexBuffer, null);

        indexBufferSize = numFaces * VERTICES_PER_FACE;

        if (numberOfVertices > 0x10000) { // > 64K
            return buildNativeGeometry(vertexBuffer,
                    numberOfVertices * VERTEX_SIZE_VB, indexBuffer, indexBufferSize);
        } else {

            if (indexBufferShort == null || indexBufferShort.length < indexBufferSize) {
                indexBufferShort = new short[indexBufferSize];
            }
            int ii = 0;
            for (int i = 0; i < numFaces; i++) {
                indexBufferShort[ii] = (short) indexBuffer[ii++];
                indexBufferShort[ii] = (short) indexBuffer[ii++];
                indexBufferShort[ii] = (short) indexBuffer[ii++];
            }
            indexBuffer = null; // free
            return buildNativeGeometry(vertexBuffer,
                    numberOfVertices * VERTEX_SIZE_VB, indexBufferShort, indexBufferSize);
        }
    }

    // Update PointNormalTexCoordGeometry
    private boolean updatePNTCGeometry(VertexFormat vertexFormat,
            float[] points, int[] pointsFromAndLengthIndices,
            float[] normals, int[] normalsFromAndLengthIndices,
            float[] texCoords, int[] texCoordsFromAndLengthIndices,
            float[] colors, int[] colorsFromAndLengthIndices) {

        if (dirtyVertices == null) {
            // Create a dirty array of size equal to number of vertices in vertexBuffer.
            dirtyVertices = new boolean[numberOfVertices];
        }
        // Clear dirty array before use.
        Arrays.fill(dirtyVertices, false);

        // Find out the list of modified points
        int startPoint = pointsFromAndLengthIndices[0] / POINT_SIZE;
        int numPoints = (pointsFromAndLengthIndices[1] / POINT_SIZE);
        if ((pointsFromAndLengthIndices[1] % POINT_SIZE) > 0) {
            numPoints++;
        }
        if (numPoints > 0) {
            for (int i = 0; i < numPoints; i++) {
                int pointOffset = (startPoint + i) * POINT_SIZE;
                MeshGeomComp2VB mp2vb = point2vbMap.get(pointOffset);
                assert mp2vb != null;
                // mp2vb shouldn't be null. We can't have a point referred by
                // the faces array that isn't in the vertexBuffer.
                if (mp2vb != null) {
                    int[] locs = mp2vb.getLocs();
                    int validLocs = mp2vb.getValidLocs();
                    if (locs != null) {
                        for (int j = 0; j < validLocs; j++) {
                            int vbIndex = locs[j] * VERTEX_SIZE_VB;
                            vertexBuffer[vbIndex] = points[pointOffset];
                            vertexBuffer[vbIndex + 1] = points[pointOffset + 1];
                            vertexBuffer[vbIndex + 2] = points[pointOffset + 2];
                            dirtyVertices[locs[j]] = true;
                        }
                    } else {
                        int loc = mp2vb.getLoc();
                        int vbIndex = loc * VERTEX_SIZE_VB;
                        vertexBuffer[vbIndex] = points[pointOffset];
                        vertexBuffer[vbIndex + 1] = points[pointOffset + 1];
                        vertexBuffer[vbIndex + 2] = points[pointOffset + 2];
                        dirtyVertices[loc] = true;
                    }
                }
            }
        }

        // Find out the list of modified tex coords.
        int startTexCoord = texCoordsFromAndLengthIndices[0] / TEXCOORD_SIZE;
        int numTexCoords = (texCoordsFromAndLengthIndices[1] / TEXCOORD_SIZE);
        if ((texCoordsFromAndLengthIndices[1] % TEXCOORD_SIZE) > 0) {
            numTexCoords++;
        }
        if (numTexCoords > 0) {
            for (int i = 0; i < numTexCoords; i++) {
                int texCoordOffset = (startTexCoord + i) * TEXCOORD_SIZE;
                MeshGeomComp2VB mt2vb = texCoord2vbMap.get(texCoordOffset);
                assert mt2vb != null;
                // mt2vb shouldn't be null. We can't have a texCoord referred by
                // the faces array that isn't in the vertexBuffer.
                if (mt2vb != null) {
                    int[] locs = mt2vb.getLocs();
                    int validLocs = mt2vb.getValidLocs();
                    if (locs != null) {
                        for (int j = 0; j < validLocs; j++) {
                            int vbIndex = (locs[j] * VERTEX_SIZE_VB) + POINT_SIZE_VB;
                            vertexBuffer[vbIndex] = texCoords[texCoordOffset];
                            vertexBuffer[vbIndex + 1] = texCoords[texCoordOffset + 1];
                            dirtyVertices[locs[j]] = true;
                        }
                    } else {
                        int loc = mt2vb.getLoc();
                        int vbIndex = (loc * VERTEX_SIZE_VB) + POINT_SIZE_VB;
                        vertexBuffer[vbIndex] = texCoords[texCoordOffset];
                        vertexBuffer[vbIndex + 1] = texCoords[texCoordOffset + 1];
                        dirtyVertices[loc] = true;
                    }
                }
            }
        }

        // Find out the list of modified normals
        int startNormal = normalsFromAndLengthIndices[0] / NORMAL_SIZE;
        int numNormals = (normalsFromAndLengthIndices[1] / NORMAL_SIZE);
        if ((normalsFromAndLengthIndices[1] % NORMAL_SIZE) > 0) {
            numNormals++;
        }
        if (numNormals > 0) {
            for (int i = 0; i < numNormals; i++) {
                int normalOffset = (startNormal + i) * NORMAL_SIZE;
                MeshGeomComp2VB mn2vb = normal2vbMap.get(normalOffset);
                assert mn2vb != null;
                // mn2vb shouldn't be null. We can't have a normal referred by
                // the faces array that isn't in the vertexBuffer.
                if (mn2vb != null) {
                    int[] locs = mn2vb.getLocs();
                    int validLocs = mn2vb.getValidLocs();
                    if (locs != null) {
                        for (int j = 0; j < validLocs; j++) {
                            int index = locs[j] * NORMAL_SIZE;
                            cachedNormals[index] = normals[normalOffset];
                            cachedNormals[index + 1] = normals[normalOffset + 1];
                            cachedNormals[index + 2] = normals[normalOffset + 2];
                            dirtyVertices[locs[j]] = true;
                        }
                    } else {
                        int loc = mn2vb.getLoc();
                        int index = loc * NORMAL_SIZE;
                            cachedNormals[index] = normals[normalOffset];
                            cachedNormals[index + 1] = normals[normalOffset + 1];
                            cachedNormals[index + 2] = normals[normalOffset + 2];
                            dirtyVertices[loc] = true;
                    }
                }
            }
        }

        // Find out the list of modified colors.
        if (colorsFromAndLengthIndices != null) {
            int startVtxColor = colorsFromAndLengthIndices[0] / COLOR_SIZE;
            int numVtxColors = (colorsFromAndLengthIndices[1] / COLOR_SIZE);
            if ((colorsFromAndLengthIndices[1] % COLOR_SIZE) > 0) {
                numVtxColors++;
            }
            if (numVtxColors > 0) {
                for (int i = 0; i < numVtxColors; i++) {
                    int vtxColorOffset = (startVtxColor + i) * COLOR_SIZE;
                    MeshGeomComp2VB mc2vb = color2vbMap.get(vtxColorOffset);
                    assert mc2vb != null;
                    // mc2vb shouldn't be null. We can't have a color referred by
                    // the faces array that isn't in the vertexBuffer.
                    if (mc2vb != null) {
                        int[] locs = mc2vb.getLocs();
                        int validLocs = mc2vb.getValidLocs();
                        if (locs != null) {
                            for (int j = 0; j < validLocs; j++) {
                                int vbIndex =
                                        (locs[j] * VERTEX_SIZE_VB) + POINT_SIZE_VB + TEXCOORD_SIZE_VB + NORMAL_SIZE_VB;
                                writeColorsToBuffer(colors, vtxColorOffset, vertexBuffer, vbIndex);
                                dirtyVertices[locs[j]] = true;
                            }
                        } else {
                            int loc = mc2vb.getLoc();
                            int vbIndex = (loc * VERTEX_SIZE_VB) + POINT_SIZE_VB + TEXCOORD_SIZE_VB + NORMAL_SIZE_VB;
                            writeColorsToBuffer(colors, vtxColorOffset, vertexBuffer, vbIndex);
                            dirtyVertices[loc] = true;
                        }
                    }
                }
            }
        }

        // Prepare process all dirty vertices
        MeshTempState instance = MeshTempState.getInstance();
        for (int i = 0; i < VERTICES_PER_FACE; i++) {
            if (instance.triPoints[i] == null) {
                instance.triPoints[i] = new Vec3f();
            }
            if (instance.triTexCoords[i] == null) {
                instance.triTexCoords[i] = new Vec2f();
            }
        }
        // Every 3 vertices form a triangle
        for (int j = 0; j < numberOfVertices; j += VERTICES_PER_FACE) {
            // Only process the triangle that has one of more dirty vertices
            if (dirtyVertices[j] || dirtyVertices[j+1] || dirtyVertices[j+2]) {
                int vbIndex = j * VERTEX_SIZE_VB;
                // Go thro. the 3 vertices of a triangle
                for (int i = 0; i < VERTICES_PER_FACE; i++) {
                    instance.triPoints[i].x = vertexBuffer[vbIndex];
                    instance.triPoints[i].y = vertexBuffer[vbIndex + 1];
                    instance.triPoints[i].z = vertexBuffer[vbIndex + 2];
                    instance.triTexCoords[i].x = vertexBuffer[vbIndex + POINT_SIZE_VB];
                    instance.triTexCoords[i].y = vertexBuffer[vbIndex + POINT_SIZE_VB + 1];
                    vbIndex += VERTEX_SIZE_VB;
                }

                MeshUtil.computeTBNNormalized(instance.triPoints[0], instance.triPoints[1],
                        instance.triPoints[2], instance.triTexCoords[0],
                        instance.triTexCoords[1], instance.triTexCoords[2],
                        instance.triNormals);

                int index = j * NORMAL_SIZE;
                for (int i = 0; i < VERTICES_PER_FACE; i++) {
                    cachedTangents[index] = instance.triNormals[1].x;
                    cachedTangents[index + 1] = instance.triNormals[1].y;
                    cachedTangents[index + 2] = instance.triNormals[1].z;
                    cachedBitangents[index] = instance.triNormals[2].x;
                    cachedBitangents[index + 1] = instance.triNormals[2].y;
                    cachedBitangents[index + 2] = instance.triNormals[2].z;
                    index += NORMAL_SIZE;
                }

            }
        }

        convertNormalsToQuats(instance, numberOfVertices,
                cachedNormals, cachedTangents, cachedBitangents, vertexBuffer, dirtyVertices);

        if (indexBuffer != null) {
            return buildNativeGeometry(vertexBuffer,
                    numberOfVertices * VERTEX_SIZE_VB, indexBuffer, indexBufferSize);
        } else {
            return buildNativeGeometry(vertexBuffer,
                    numberOfVertices * VERTEX_SIZE_VB, indexBufferShort, indexBufferSize);
        }

    }

    @Override
    public boolean buildGeometry(VertexFormat vertexFormat,
            boolean userDefinedNormals, boolean userDefinedColors,
            float[] points, int[] pointsFromAndLengthIndices,
            float[] normals, int[] normalsFromAndLengthIndices,
            float[] texCoords, int[] texCoordsFromAndLengthIndices,
            float[] colors, int[] colorsFromAndLengthIndices,
            int[] faces, int[] facesFromAndLengthIndices,
            int[] faceSmoothingGroups, int[] faceSmoothingGroupsFromAndLengthIndices) {
        if (userDefinedNormals) {
            if (userDefinedColors) {
                return buildPNTCGeometry(vertexFormat,
                        points, pointsFromAndLengthIndices,
                        normals, normalsFromAndLengthIndices,
                        texCoords, texCoordsFromAndLengthIndices,
                        colors, colorsFromAndLengthIndices,
                        faces, facesFromAndLengthIndices);
            } else {
                return buildPNTGeometry(vertexFormat,
                        points, pointsFromAndLengthIndices,
                        normals, normalsFromAndLengthIndices,
                        texCoords, texCoordsFromAndLengthIndices,
                        faces, facesFromAndLengthIndices);
            }
        } else if (userDefinedColors) {
            return buildPTCGeometry(vertexFormat, points, texCoords, colors, faces, faceSmoothingGroups);
        } else {
            return buildPTCGeometry(vertexFormat, points, texCoords, null, faces, faceSmoothingGroups);
        }
    }

    // Build PointNormalTexCoordGeometry
    private boolean buildPNTCGeometry(VertexFormat vertexFormat,
            float[] points, int[] pointsFromAndLengthIndices,
            float[] normals, int[] normalsFromAndLengthIndices,
            float[] texCoords, int[] texCoordsFromAndLengthIndices,
            float[] colors, int[] colorsFromAndLengthIndices,
            int[] faces, int[] facesFromAndLengthIndices) {

        boolean updatePoints = pointsFromAndLengthIndices[1] > 0;
        boolean updateNormals = normalsFromAndLengthIndices[1] > 0;
        boolean updateTexCoords = texCoordsFromAndLengthIndices[1] > 0;
        boolean updateColors = colorsFromAndLengthIndices[1] > 0;
        boolean updateFaces = facesFromAndLengthIndices[1] > 0;

        // First time creation
        boolean buildGeom = !(updatePoints || updateNormals || updateTexCoords || updateColors || updateFaces);

        // We will need to rebuild geom buffers if there is a change to faces
        if (updateFaces) {
            buildGeom = true;
        }

        if ((!buildGeom) && (vertexBuffer != null)
                && ((indexBuffer != null) || (indexBufferShort != null))) {
            return updatePNTCGeometry(vertexFormat,
                    points, pointsFromAndLengthIndices,
                    normals, normalsFromAndLengthIndices,
                    texCoords, texCoordsFromAndLengthIndices,
                    colors, colorsFromAndLengthIndices);
        }
        return doBuildPNTCGeometry(vertexFormat, points, normals, texCoords, colors, faces);

    }

    // Build PointNormalTexCoordGeometry
    private boolean buildPNTGeometry(VertexFormat vertexFormat,
            float[] points, int[] pointsFromAndLengthIndices,
            float[] normals, int[] normalsFromAndLengthIndices,
            float[] texCoords, int[] texCoordsFromAndLengthIndices,
            int[] faces, int[] facesFromAndLengthIndices) {

        boolean updatePoints = pointsFromAndLengthIndices[1] > 0;
        boolean updateNormals = normalsFromAndLengthIndices[1] > 0;
        boolean updateTexCoords = texCoordsFromAndLengthIndices[1] > 0;
        boolean updateFaces = facesFromAndLengthIndices[1] > 0;

        // First time creation
        boolean buildGeom = !(updatePoints || updateNormals || updateTexCoords || updateFaces);

        // We will need to rebuild geom buffers if there is a change to faces
        if (updateFaces) {
            buildGeom = true;
        }

        if ((!buildGeom) && (vertexBuffer != null)
                && ((indexBuffer != null) || (indexBufferShort != null))) {
            return updatePNTCGeometry(vertexFormat,
                    points, pointsFromAndLengthIndices,
                    normals, normalsFromAndLengthIndices,
                    texCoords, texCoordsFromAndLengthIndices,
                    null, null);
        }
        return doBuildPNTCGeometry(vertexFormat, points, normals, texCoords, null, faces);

    }

    // Build PointTexCoordGeometry
    private boolean buildPTCGeometry(VertexFormat vertexFormat,
                                     float[] pos, float[] uv, float[] colors, int[] faces, int[] smoothing) {
        nVerts = pos.length / POINT_SIZE;
        nTVerts = uv.length / TEXCOORD_SIZE;
        nFaces = faces.length / (vertexFormat.getVertexIndexSize() * VERTICES_PER_FACE);
        assert nVerts > 0 && nFaces > 0 && nTVerts > 0;
        this.pos = pos;
        this.colors = colors;
        this.uv = uv;
        this.faces = faces;
        this.smoothing = smoothing.length == nFaces ? smoothing : null;

        MeshTempState instance = MeshTempState.getInstance();
        // big pool for all possible vertices
        if (instance.pool == null || instance.pool.length < nFaces * VERTICES_PER_FACE) {
            instance.pool = new MeshVertex[nFaces * VERTICES_PER_FACE];
        }

        if (instance.indexBuffer == null || instance.indexBuffer.length < nFaces * VERTICES_PER_FACE) {
            instance.indexBuffer = new int[nFaces * VERTICES_PER_FACE];
        }

        if (instance.pVertex == null || instance.pVertex.length < nVerts) {
            instance.pVertex = new MeshVertex[nVerts];
        } else {
            Arrays.fill(instance.pVertex, 0, instance.pVertex.length, null);
        }

        // check if all hard edges or all smooth
        checkSmoothingGroup();

        // compute [N, T, B] for each face
        computeTBNormal(vertexFormat, instance.pool, instance.pVertex, instance.indexBuffer);

        // process sm and weld points
        int nNewVerts = MeshVertex.processVertices(instance.pVertex, nVerts,
                allHardEdges, allSameSmoothing);

        if (instance.vertexBuffer == null
                || instance.vertexBuffer.length < nNewVerts * VERTEX_SIZE_VB) {
            instance.vertexBuffer = new float[nNewVerts * VERTEX_SIZE_VB];
        }
        buildVertexBuffer(instance.pVertex, instance.vertexBuffer);

        if (nNewVerts > 0x10000) {
            buildIndexBuffer(instance.pool, instance.indexBuffer, null);
            return buildNativeGeometry(instance.vertexBuffer, nNewVerts * VERTEX_SIZE_VB,
                    instance.indexBuffer, nFaces * VERTICES_PER_FACE);
        } else {
            if (instance.indexBufferShort == null || instance.indexBufferShort.length < nFaces * VERTICES_PER_FACE) {
                instance.indexBufferShort = new short[nFaces * VERTICES_PER_FACE];
            }
            buildIndexBuffer(instance.pool, instance.indexBuffer, instance.indexBufferShort);
            return buildNativeGeometry(instance.vertexBuffer,
                    nNewVerts * VERTEX_SIZE_VB, instance.indexBufferShort, nFaces * VERTICES_PER_FACE);
        }
    }

    private void computeTBNormal(VertexFormat vtxFormat, MeshVertex[] pool, MeshVertex[] pVertex, int[] indexBuffer) {
        MeshTempState instance = MeshTempState.getInstance();

        // tmp variables
        int[] smFace = instance.smFace;
        int[] triVerts = instance.triVerts;
        Vec3f[] triPoints = instance.triPoints;
        Vec2f[] triTexCoords = instance.triTexCoords;
        Vec3f[] triNormals = instance.triNormals;
        final String logname = BaseMesh.class.getName();

        for (int f = 0, nDeadFaces = 0, poolIndex = 0; f < nFaces; f++) {
            int index = f * VERTICES_PER_FACE;

            smFace = getFace(vtxFormat, f, smFace); // copy from mesh to tmp smFace

            // Get tex. point. index
            triVerts[0] = smFace[BaseMesh.FaceMembers.POINT0.ordinal()];
            triVerts[1] = smFace[BaseMesh.FaceMembers.POINT1.ordinal()];
            triVerts[2] = smFace[BaseMesh.FaceMembers.POINT2.ordinal()];

            if (MeshUtil.isDeadFace(triVerts)
                    && PlatformLogger.getLogger(logname).isLoggable(PlatformLogger.Level.FINE)) {
                // Log degenerated triangle
                nDeadFaces++;
                PlatformLogger.getLogger(logname).fine("Dead face ["
                        + triVerts[0] + ", " + triVerts[1] + ", " + triVerts[2]
                        + "] @ face group " + f + "; nEmptyFaces = " + nDeadFaces);
            }

            for (int i = 0; i < VERTICES_PER_FACE; i++) {
                triPoints[i] = getVertex(triVerts[i], triPoints[i]);
            }

            // Get tex. coord. index
            triVerts[0] = smFace[BaseMesh.FaceMembers.TEXCOORD0.ordinal()];
            triVerts[1] = smFace[BaseMesh.FaceMembers.TEXCOORD1.ordinal()];
            triVerts[2] = smFace[BaseMesh.FaceMembers.TEXCOORD2.ordinal()];

            for (int i = 0; i < VERTICES_PER_FACE; i++) {
                triTexCoords[i] = getTVertex(triVerts[i], triTexCoords[i]);
            }

            MeshUtil.computeTBNNormalized(triPoints[0], triPoints[1], triPoints[2],
                                          triTexCoords[0], triTexCoords[1], triTexCoords[2],
                                          triNormals);

            for (int j = 0; j < VERTICES_PER_FACE; ++j) {
                pool[poolIndex] = (pool[poolIndex] == null) ? new MeshVertex() : pool[poolIndex];

                for (int i = 0; i < VERTICES_PER_FACE; ++i) {
                    pool[poolIndex].norm[i].set(triNormals[i]);
                }
                pool[poolIndex].smGroup = smFace[BaseMesh.FaceMembers.SMOOTHING_GROUP.ordinal()];
                pool[poolIndex].fIdx = f;
                pool[poolIndex].tVert = triVerts[j];
                pool[poolIndex].index = MeshVertex.IDX_UNDEFINED;

                BaseMesh.FaceMembers pointMember;
                BaseMesh.FaceMembers colorMember;
                switch (j) {
                    case 0:
                        pointMember = BaseMesh.FaceMembers.POINT0;
                        colorMember = BaseMesh.FaceMembers.COLOR0;
                        break;
                    case 1:
                        pointMember = BaseMesh.FaceMembers.POINT1;
                        colorMember = BaseMesh.FaceMembers.COLOR1;
                        break;
                    case 2:
                    default:
                        pointMember = BaseMesh.FaceMembers.POINT2;
                        colorMember = BaseMesh.FaceMembers.COLOR2;
                        break;
                }

                int pIdx = smFace[pointMember.ordinal()];
                int cIdx = smFace[colorMember.ordinal()];
                pool[poolIndex].pVert = pIdx;
                pool[poolIndex].cVert = cIdx;
                indexBuffer[index + j] = pIdx;
                pool[poolIndex].next = pVertex[pIdx];
                pVertex[pIdx] = pool[poolIndex];
                poolIndex++;
            }
        }
    }

    private void buildVSQuat(Vec3f[] tm, Quat4f quat) {
        Vec3f v = MeshTempState.getInstance().vec3f1;
        v.cross(tm[1], tm[2]);
        float d = tm[0].dot(v);
        if (d < 0) {
            tm[2].mul(-1);
        }

        MeshUtil.buildQuat(tm, quat);

        // This will interfer with degenerated triangle unit test.
        // assert (quat.w >= 0);

        if (d < 0) {
            if (quat.w == 0) {
                quat.w = MeshUtil.MAGIC_SMALL;
            }
            quat.scale(-1);
        }
    }

    private void buildVertexBuffer(MeshVertex[] pVerts, float[] vertexBuffer) {
        Quat4f quat = MeshTempState.getInstance().quat;
        int idLast = 0;

        for (int i = 0, index = 0; i < nVerts; ++i) {
            MeshVertex v = pVerts[i];
            for (; v != null; v = v.next) {
                if (v.index == idLast) {
                    int ind = v.pVert * POINT_SIZE;
                    vertexBuffer[index++] = pos[ind];
                    vertexBuffer[index++] = pos[ind + 1];
                    vertexBuffer[index++] = pos[ind + 2];
                    ind = v.tVert * TEXCOORD_SIZE;
                    vertexBuffer[index++] = uv[ind];
                    vertexBuffer[index++] = uv[ind + 1];
                    buildVSQuat(v.norm, quat);
                    vertexBuffer[index++] = quat.x;
                    vertexBuffer[index++] = quat.y;
                    vertexBuffer[index++] = quat.z;
                    vertexBuffer[index++] = quat.w;
                    ind = v.cVert * COLOR_SIZE;
                    index = writeColorsToBuffer(colors, ind, vertexBuffer, index);
                    idLast++;
                }
            }
        }
    }

    private static int writeColorsToBuffer(float[] colors, int colorIndex, float[] outputVertexBuffer, int outputIndex) {
        if (colors != null && colors.length >= colorIndex + COLOR_SIZE && colorIndex >= 0) {
            outputVertexBuffer[outputIndex++] = colors[colorIndex]; // Red
            outputVertexBuffer[outputIndex++] = colors[colorIndex + 1]; // Green
            outputVertexBuffer[outputIndex++] = colors[colorIndex + 2]; // Blue
            outputVertexBuffer[outputIndex++] = colors[colorIndex + 3]; // Alpha
        } else {
            outputVertexBuffer[outputIndex++] = 1F;
            outputVertexBuffer[outputIndex++] = 1F;
            outputVertexBuffer[outputIndex++] = 1F;
            outputVertexBuffer[outputIndex++] = 1F;
        }

        return outputIndex;
    }

    private void buildIndexBuffer(MeshVertex[] pool, int[] indexBuffer, short[] indexBufferShort) {
        for (int i = 0; i < nFaces; ++i) {
            int index = i * VERTICES_PER_FACE;
            if (indexBuffer[index] != MeshVertex.IDX_UNDEFINED) {
                for (int j = 0; j < VERTICES_PER_FACE; ++j) {
                    assert (pool[index].fIdx == i);
                    if (indexBufferShort != null) {
                        indexBufferShort[index + j] = (short) pool[index + j].index;
                    } else {
                        indexBuffer[index + j] = pool[index + j].index;
                    }
                    pool[index + j].next = null; // release reference
                }
            } else {
                for (int j = 0; j < VERTICES_PER_FACE; ++j) {
                    if (indexBufferShort != null) {
                        indexBufferShort[index + j] = 0;
                    } else {
                        indexBuffer[index + j] = 0;
                    }
                }
            }
        }
    }

    public int getNumVerts() {
        return nVerts;
    }

    public int getNumTVerts() {
        return nTVerts;
    }

    public int getNumFaces() {
        return nFaces;
    }

    public Vec3f getVertex(int pIdx, Vec3f vertex) {
        if (vertex == null) {
            vertex = new Vec3f();
        }
        int index = pIdx * POINT_SIZE;
        vertex.set(pos[index], pos[index + 1], pos[index + 2]);
        return vertex;
    }

    public Vec2f getTVertex(int tIdx, Vec2f texCoord) {
        if (texCoord == null) {
            texCoord = new Vec2f();
        }
        int index = tIdx * TEXCOORD_SIZE;
        texCoord.set(uv[index], uv[index + 1]);
        return texCoord;
    }

    public Vec4f getColor(int tIdx, Vec4f color) {
        if (color == null) {
            color = new Vec4f();
        }
        int index = tIdx * COLOR_SIZE;
        color.set(colors[index], colors[index + 1], colors[index + 2], colors[index + 3]);
        return color;
    }

    private void checkSmoothingGroup() {
        if (smoothing == null || smoothing.length == 0) { // all smooth
            allSameSmoothing = true;
            allHardEdges = false;
            return;
        }

        for (int i = 0; i + 1 < smoothing.length; i++) {
            if (smoothing[i] != smoothing[i + 1]) {
                // various SmGroup
                allSameSmoothing = false;
                allHardEdges = false;
                return;
            }
        }

        if (smoothing[0] == 0) { // all hard edges
            allSameSmoothing = false;
            allHardEdges = true;
        } else { // all belongs to one group == all smooth
            allSameSmoothing = true;
            allHardEdges = false;
        }
    }

    // Only valid when the VertexFormat does not contain normal data.
    public int[] getFace(VertexFormat vertexFormat, int fIdx, int[] face) {
        int vertexIndexSize = vertexFormat.getVertexIndexSize();
        int faceMemberCopyCount = (vertexIndexSize * VERTICES_PER_FACE);

        int index = fIdx * faceMemberCopyCount;
        if ((face == null) || (face.length < FACE_MEMBERS_SIZE)) {
            face = new int[FACE_MEMBERS_SIZE];
        }
        // Note: Order matters,
        for (int i = 0; i < VERTICES_PER_FACE; i++) {
            int baseIndex = (3 * i);
            int srcIndex = index + (i * vertexIndexSize);
            face[baseIndex] = faces[srcIndex + vertexFormat.getPointIndexOffset()];
            face[baseIndex + 1] = faces[srcIndex + vertexFormat.getTexCoordIndexOffset()];
            if (vertexFormat.getColorIndexOffset() >= 0) {
                face[baseIndex + 2] = faces[srcIndex + vertexFormat.getColorIndexOffset()];
            } else {
                face[baseIndex + 2] = -1;
            }
        }
        // Note: Order matter, FACE_MEMBERS_COPIED_SIZE == FaceMembers.SMOOTHING_GROUP.ordinal()
        // There is a total of 32 smoothing groups.
        // Assign to 1st smoothing group if smoothing is null.
        face[FACE_MEMBERS_COPIED_SIZE] = smoothing != null ? smoothing[fIdx] : 1;
        return face;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    // Package scope method for testing
    boolean test_isVertexBufferNull() {
        return vertexBuffer == null;
    }

    // Package scope method for testing
    int test_getVertexBufferLength() {
        return vertexBuffer.length;
    }

    // Package scope method for testing
    int test_getNumberOfVertices() {
        return numberOfVertices;
    }

    class MeshGeomComp2VB {

        private final int key; // point or texCoord index
        private final int loc; // the first index into vertex buffer
        private int[] locs;
        private int validLocs;

        MeshGeomComp2VB(int key, int loc) {
            assert loc >= 0;
            this.key = key;
            this.loc = loc;
            locs = null;
            validLocs = 0;
        }

        void addLoc(int loc) {
            if (locs == null) {
                locs = new int[VERTICES_PER_FACE]; // edge of mesh case
                locs[0] = this.loc;
                locs[1] = loc;
                this.validLocs = 2;
            } else if (locs.length > validLocs) {
                locs[validLocs] = loc;
                validLocs++;
            } else {
                int[] temp = new int[validLocs * 2];
                System.arraycopy(locs, 0, temp, 0, locs.length);
                locs = temp;
                locs[validLocs] = loc;
                validLocs++;
            }
        }

        int getKey() {
            return key;
        }

        int getLoc() {
            return loc;
        }

        int[] getLocs() {
            return locs;
        }

        int getValidLocs() {
            return validLocs;
        }
    }

}
