/*
 *  Copyright 2019 Carnegie Mellon University
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package edu.cmu.lti.oaqa.knn4qa.utils;

/**
 * A an id-value pair class.
 * 
 * @author Leonid Boytsov
 *
 */
public class IdValPair implements Comparable<IdValPair> {
  
  public final int mId;
  public final float mVal;
  
  public IdValPair(int mId, float mVal) {
    this.mId = mId;
    this.mVal = mVal;
  }
  
  @Override
  public String toString() {
    return mId + ":" + mVal;
  }

  // Smaller ID go first
  @Override
  public int compareTo(IdValPair o) {
    return mId - o.mId;
  }
  
}
