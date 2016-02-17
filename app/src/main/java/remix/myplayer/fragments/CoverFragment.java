package remix.myplayer.fragments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;


import remix.myplayer.R;
import remix.myplayer.activities.AudioHolderActivity;
import remix.myplayer.utils.Constants;
import remix.myplayer.utils.DBUtil;
import remix.myplayer.utils.MP3Info;

/**
 * Created by Remix on 2015/12/2.
 */
public class CoverFragment extends Fragment {

    private ImageView mImage;
    private Bitmap mBitmap;
    private MP3Info mInfo;
    private TranslateAnimation mLeftAnimation;
    private ScaleAnimation mScaleAnimation;
    private TranslateAnimation mRightAnimation;
    private Bitmap mNewBitmap;
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mInfo = (MP3Info)getArguments().getSerializable("MP3Info");
        View rootView = inflater.inflate(R.layout.cover,container,false);
        mImage = (ImageView)rootView.findViewById(R.id.cover_image);
//        mImage.setBackgroundResource(R.drawable.bg_cover_corners);
//        mImage.setMinimumHeight(AudioHolderActivity.mWidth - 10);
//        mImage.setMinimumWidth(AudioHolderActivity.mWidth - 10);
        if(mInfo != null && (mBitmap = DBUtil.CheckBitmapBySongId((int)mInfo.getId(),false)) != null)
            mImage.setImageBitmap(mBitmap);
        if(mLeftAnimation == null || mScaleAnimation == null || mRightAnimation == null)
        {
            mLeftAnimation = (TranslateAnimation) AnimationUtils.loadAnimation(getContext(),R.anim.cover_left_out);
            mRightAnimation = (TranslateAnimation)AnimationUtils.loadAnimation(getContext(),R.anim.cover_right_out);
            mScaleAnimation = (ScaleAnimation) AnimationUtils.loadAnimation(getContext(),R.anim.cover_center_in);

            Animation.AnimationListener listener = new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                }
                @Override
                public void onAnimationEnd(Animation animation) {
                    if(mNewBitmap != null)
                        mImage.setImageBitmap(mNewBitmap);
                    else
                        mImage.setImageBitmap(BitmapFactory.decodeResource(getResources(),R.drawable.no_art_normal));
                    mImage.startAnimation(mScaleAnimation);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                }
            };
            mLeftAnimation.setAnimationListener(listener);
            mRightAnimation.setAnimationListener(listener);

        }
        return rootView;
//        LinearLayout layout = new LinearLayout(getContext());
//        layout.setLayoutParams(new  ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
//        mImage = new ImageView(getContext());
//        mBitmap = BitmapFactory.decodeFile(mInfo.getAlbumArt());
//        if(mBitmap != null)
//            mImage.setImageBitmap(mBitmap);
//        else
//            mImage.setImageResource(R.drawable.no_art_normal);
//        mImage.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
//        layout.addView(mImage);
//        return layout;
    }
    public void UpdateCover(Bitmap bitmap)
    {
        if(!isAdded())
            return;
        if (mImage == null)
            return;
        mNewBitmap = bitmap;
        if(AudioHolderActivity.mOperation == Constants.PREV)
            mImage.startAnimation(mRightAnimation);
        else
            mImage.startAnimation(mLeftAnimation);
    }
}
