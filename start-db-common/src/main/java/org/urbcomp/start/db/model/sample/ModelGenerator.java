/*
 * Copyright 2022 ST-Lab
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 */

package org.urbcomp.start.db.model.sample;

import com.alibaba.fastjson.JSON;
import org.urbcomp.start.db.model.point.GPSPoint;
import org.urbcomp.start.db.model.point.SpatialPoint;
import org.urbcomp.start.db.model.roadnetwork.RoadNetwork;
import org.urbcomp.start.db.model.roadnetwork.RoadSegment;
import org.urbcomp.start.db.model.roadnetwork.RoadSegmentDirection;
import org.urbcomp.start.db.model.roadnetwork.RoadSegmentLevel;
import org.urbcomp.start.db.model.trajectory.Trajectory;
import org.urbcomp.start.db.util.WKTUtils;

import java.io.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ModelGenerator {
    public static Trajectory generateTrajectory() {
        String fileName = Objects.requireNonNull(
            ModelGenerator.class.getClassLoader().getResource("data/traj.txt")
        ).getPath();

        try (
            InputStream in = new FileInputStream(fileName);
            BufferedReader br = new BufferedReader(new InputStreamReader(in))
        ) {
            String trajStr = br.readLine();
            String correctStr = trajStr.replaceFirst("\\[", "[\"").replaceFirst(",", "\",");
            List<String> result = JSON.parseArray(correctStr, String.class);
            String oid = result.get(0);
            List<String> pointsStrList = JSON.parseArray(result.get(1), String.class);
            List<GPSPoint> pointsList = pointsStrList.stream()
                .map(o -> JSON.parseArray(o, String.class))
                .map(
                    o -> new GPSPoint(
                        Timestamp.valueOf(o.get(0)),
                        Double.parseDouble(o.get(1)),
                        Double.parseDouble(o.get(2))
                    )
                )
                .collect(Collectors.toList());
            return new Trajectory(oid + pointsList.get(0).getTime(), oid, pointsList);
        } catch (IOException e) {
            throw new RuntimeException("Generate trajectory error: " + e.getMessage());
        }
    }

    public static RoadSegment generateRoadSegment() {
        List<SpatialPoint> points = new ArrayList<>();
        points.add(new SpatialPoint(111.37939453125, 54.00776876193478));
        points.add(new SpatialPoint(116.3671875, 53.05442186546102));
        return new RoadSegment(1, 1, 2, points).setDirection(RoadSegmentDirection.DUAL)
            .setSpeedLimit(30.0)
            .setLevel(RoadSegmentLevel.URBAN_ROAD)
            .setLengthInMeter(120);
    }

    public static RoadNetwork generateRoadNetwork() {
        String fileName = Objects.requireNonNull(
            ModelGenerator.class.getClassLoader().getResource("data/roadnetwork_gcj02.csv")
        ).getPath();
        try (
            InputStream in = new FileInputStream(fileName);
            BufferedReader br = new BufferedReader(new InputStreamReader(in))
        ) {
            br.readLine(); // read head
            String roadSegmentStr;
            List<RoadSegment> roadSegments = new ArrayList<>();
            while ((roadSegmentStr = br.readLine()) != null) {
                String[] roadStrArr = roadSegmentStr.split("\\|");
                int roadSegmentId = Integer.parseInt(roadStrArr[0]);
                int startId = Integer.parseInt(roadStrArr[2]);
                int endId = Integer.parseInt(roadStrArr[3]);
                RoadSegmentDirection direction = RoadSegmentDirection.valueOf(
                    Integer.parseInt(roadStrArr[4])
                );
                RoadSegmentLevel level = RoadSegmentLevel.valueOf(Integer.parseInt(roadStrArr[5]));
                double speedLimit = Double.parseDouble(roadStrArr[6]);
                double lengthInM = Double.parseDouble(roadStrArr[7]);
                List<SpatialPoint> points = Arrays.stream(
                    WKTUtils.read(roadStrArr[1]).getCoordinates()
                ).map(SpatialPoint::new).collect(Collectors.toList());
                RoadSegment rs = new RoadSegment(roadSegmentId, startId, endId, points);
                rs.setDirection(direction)
                    .setLevel(level)
                    .setSpeedLimit(speedLimit)
                    .setLengthInMeter(lengthInM);
                roadSegments.add(rs);
            }
            return new RoadNetwork(roadSegments);
        } catch (Exception e) {
            throw new RuntimeException("Generate road network error: " + e.getMessage());
        }
    }
}
