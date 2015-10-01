////////////////////////////////////////////////////////////////////////////////
//
//  Melodeon - An Android Melodeon written in C and Java.
//
//  Copyright (C) 2013	Bill Farmer
//
//  This program is free software: you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
//  Bill Farmer	 william j farmer [at] yahoo [dot] co [dot] uk.
//
///////////////////////////////////////////////////////////////////////////////

#include <jni.h>
#include <dlfcn.h>
#include <assert.h>
#include <pthread.h>

#include <android/log.h>

// for native audio
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include "org_billthefarmer_melodeon_MidiDriver.h"

// for EAS midi
#include "eas.h"
#include "eas_reverb.h"

#define LOG_TAG "MidiDriver"

#define LOG_D(tag, ...) __android_log_print(ANDROID_LOG_DEBUG, tag, __VA_ARGS__)
#define LOG_E(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)
#define LOG_I(tag, ...) __android_log_print(ANDROID_LOG_INFO, tag, __VA_ARGS__)

// determines how many EAS buffers to fill a host buffer
#define NUM_BUFFERS 4

// mutex
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;

// engine interfaces
static SLObjectItf engineObject = NULL;
static SLEngineItf engineEngine;

// output mix interfaces
static SLObjectItf outputMixObject = NULL;

// buffer queue player interfaces
static SLObjectItf bqPlayerObject = NULL;
static SLPlayItf bqPlayerPlay;
static SLAndroidSimpleBufferQueueItf bqPlayerBufferQueue;

// EAS function pointers
EAS_PUBLIC const S_EAS_LIB_CONFIG *(*pEAS_Config) (void);

EAS_PUBLIC EAS_RESULT (*pEAS_Init) (EAS_DATA_HANDLE *ppEASData);
EAS_PUBLIC EAS_RESULT (*pEAS_SetParameter) (EAS_DATA_HANDLE pEASData,
					    EAS_I32 module,
					    EAS_I32 param,
					    EAS_I32 value);
EAS_PUBLIC EAS_RESULT (*pEAS_OpenMIDIStream) (EAS_DATA_HANDLE pEASData,
					      EAS_HANDLE *pStreamHandle,
                                              EAS_HANDLE streamHandle);
EAS_PUBLIC EAS_RESULT (*pEAS_Shutdown) (EAS_DATA_HANDLE pEASData);
EAS_PUBLIC EAS_RESULT (*pEAS_Render) (EAS_DATA_HANDLE pEASData,
				      EAS_PCM *pOut,
				      EAS_I32 numRequested,
                                      EAS_I32 *pNumGenerated);
EAS_PUBLIC EAS_RESULT (*pEAS_WriteMIDIStream)(EAS_DATA_HANDLE pEASData,
					      EAS_HANDLE streamHandle,
					      EAS_U8 *pBuffer,
                                              EAS_I32 count);
EAS_PUBLIC EAS_RESULT (*pEAS_CloseMIDIStream) (EAS_DATA_HANDLE pEASData,
					       EAS_HANDLE streamHandle);

// EAS data
static EAS_DATA_HANDLE pEASData;
const S_EAS_LIB_CONFIG *pLibConfig;
static EAS_PCM *buffer;
static EAS_I32 bufferSize;
static EAS_HANDLE midiHandle;

// this callback handler is called every time a buffer finishes
// playing
void bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void *context)
{
    EAS_RESULT result;
    EAS_I32 numGenerated;
    EAS_I32 count;

    assert(bq == bqPlayerBufferQueue);
    assert(NULL == context);

    // for streaming playback, replace this test by logic to fill the
    // next buffer

    count = 0;
    while (count < bufferSize)
    {
	// lock
	pthread_mutex_lock(&mutex);

	result = pEAS_Render(pEASData, buffer + count,
			     pLibConfig->mixBufferSize, &numGenerated);
	// unlock
	pthread_mutex_unlock(&mutex);      

	assert(result == EAS_SUCCESS);

	count += numGenerated * pLibConfig->numChannels;
    }

    // enqueue another buffer
    result = (*bqPlayerBufferQueue)->Enqueue(bq, buffer,
					     bufferSize * sizeof(EAS_PCM));

    // the most likely other result is SL_RESULT_BUFFER_INSUFFICIENT,
    // which for this code example would indicate a programming error
    assert(SL_RESULT_SUCCESS == result);
}

// create the engine and output mix objects
SLresult createEngine()
{
    SLresult result;

    // create engine
    result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Engine created");

    // realize the engine
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Engine realised");

    // get the engine interface, which is needed in order to create
    // other objects
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE,
					   &engineEngine);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Engine Interface retrieved");

    // create output mix
    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject,
					      0, NULL, NULL);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Output mix created");

    // realize the output mix
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Output mix realised");

    return SL_RESULT_SUCCESS;
}

// create buffer queue audio player
SLresult createBufferQueueAudioPlayer()
{
    SLresult result;

    // configure audio source
    SLDataLocator_AndroidSimpleBufferQueue loc_bufq =
	{SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
    SLDataFormat_PCM format_pcm =
	{SL_DATAFORMAT_PCM, pLibConfig->numChannels,
	 pLibConfig->sampleRate * 1000,
	 SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
	 SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
	 SL_BYTEORDER_LITTLEENDIAN};
    SLDataSource audioSrc = {&loc_bufq, &format_pcm};

    // configure audio sink
    SLDataLocator_OutputMix loc_outmix =
	{SL_DATALOCATOR_OUTPUTMIX, outputMixObject};
    SLDataSink audioSnk = {&loc_outmix, NULL};

    // create audio player
    const SLInterfaceID ids[1] = {SL_IID_BUFFERQUEUE};
    const SLboolean req[1] = {SL_BOOLEAN_TRUE};

    result = (*engineEngine)->CreateAudioPlayer(engineEngine,
						&bqPlayerObject,
						&audioSrc, &audioSnk,
						1, ids, req);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Audio player created");

    // realize the player
    result = (*bqPlayerObject)->Realize(bqPlayerObject, SL_BOOLEAN_FALSE);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Audio player realised");

    // get the play interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_PLAY,
					     &bqPlayerPlay);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Play interface retrieved");

    // get the buffer queue interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_BUFFERQUEUE,
					     &bqPlayerBufferQueue);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Buffer queue interface retrieved");

    // register callback on the buffer queue
    result = (*bqPlayerBufferQueue)->RegisterCallback(bqPlayerBufferQueue,
						      bqPlayerCallback, NULL);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Callback registered");

    // set the player's state to playing
    result = (*bqPlayerPlay)->SetPlayState(bqPlayerPlay, SL_PLAYSTATE_PLAYING);
    if (SL_RESULT_SUCCESS != result)
	return result;

    // LOG_D(LOG_TAG, "Audio player set playing");

    return SL_RESULT_SUCCESS;
}

// shut down the native audio system
void shutdownAudio()
{
    // destroy buffer queue audio player object, and invalidate all
    // associated interfaces
    if (bqPlayerObject != NULL)
    {
        (*bqPlayerObject)->Destroy(bqPlayerObject);
        bqPlayerObject = NULL;
        bqPlayerPlay = NULL;
        bqPlayerBufferQueue = NULL;
    }

    // destroy output mix object, and invalidate all associated interfaces
    if (outputMixObject != NULL)
    {
        (*outputMixObject)->Destroy(outputMixObject);
        outputMixObject = NULL;
    }

    // destroy engine object, and invalidate all associated interfaces
    if (engineObject != NULL)
    {
        (*engineObject)->Destroy(engineObject);
        engineObject = NULL;
        engineEngine = NULL;
    }
}

// init EAS midi
EAS_RESULT initEAS()
{
    EAS_RESULT result;

    // get the library configuration
    pLibConfig = pEAS_Config();
    if (pLibConfig == NULL || pLibConfig->libVersion != LIB_VERSION)
	return EAS_FAILURE;

    // calculate buffer size
    bufferSize = pLibConfig->mixBufferSize * pLibConfig->numChannels *
	NUM_BUFFERS;

    // init library
    if ((result = pEAS_Init(&pEASData)) != EAS_SUCCESS)
        return result;

    // select reverb preset and enable
    pEAS_SetParameter(pEASData, EAS_MODULE_REVERB, EAS_PARAM_REVERB_PRESET,
                      EAS_PARAM_REVERB_CHAMBER);
    pEAS_SetParameter(pEASData, EAS_MODULE_REVERB, EAS_PARAM_REVERB_BYPASS,
                      EAS_FALSE);

    // open midi stream
    if (result = pEAS_OpenMIDIStream(pEASData, &midiHandle, NULL) !=
        EAS_SUCCESS)
	return result;

    return EAS_SUCCESS;
}

// shutdown EAS midi
void shutdownEAS()
{
    if (midiHandle != NULL)
    {
	pEAS_CloseMIDIStream(pEASData, midiHandle);
	midiHandle = NULL;
    }

    if (pEASData != NULL)
    {
	pEAS_Shutdown(pEASData);
	pEASData = NULL;
    }
}

// init mididriver
jboolean
Java_org_billthefarmer_melodeon_MidiDriver_init(JNIEnv *env,
						jobject obj)
{
    EAS_RESULT result;

    if (result = initEAS() != EAS_SUCCESS)
    {
	shutdownEAS();

	LOG_E(LOG_TAG, "Init EAS failed: %ld", result);

	return JNI_FALSE;
    }

    // LOG_D(LOG_TAG, "Init EAS success, buffer: %ld", bufferSize);

    // allocate buffer in bytes
    buffer = (EAS_PCM *)malloc(bufferSize * sizeof(EAS_PCM));
    if (buffer == NULL)
    {
	shutdownEAS();

	LOG_E(LOG_TAG, "Allocate buffer failed");

	return JNI_FALSE;
    }

    // create the engine and output mix objects
    if (result = createEngine() != SL_RESULT_SUCCESS)
    {
	shutdownEAS();
	shutdownAudio();
	free(buffer);
	buffer = NULL;

	LOG_E(LOG_TAG, "Create engine failed: %ld", result);

	return JNI_FALSE;
    }

    // create buffer queue audio player
    if (result = createBufferQueueAudioPlayer() != SL_RESULT_SUCCESS)
    {
	shutdownEAS();
	shutdownAudio();
	free(buffer);
	buffer = NULL;

	LOG_E(LOG_TAG, "Create buffer queue audio player failed: %ld", result);

	return JNI_FALSE;
    }

    // call the callback to start playing
    bqPlayerCallback(bqPlayerBufferQueue, NULL);

    return JNI_TRUE;
}

// midi config
jintArray
Java_org_billthefarmer_melodeon_MidiDriver_config(JNIEnv *env,
						   jobject obj)
{
    jboolean isCopy;

    if (pLibConfig == NULL)
	return NULL;

    jintArray configArray = env->NewIntArray(4);

    jint *config = env->GetIntArrayElements(configArray, &isCopy);

    config[0] = pLibConfig->maxVoices;
    config[1] = pLibConfig->numChannels;
    config[2] = pLibConfig->sampleRate;
    config[3] = pLibConfig->mixBufferSize;

    env->ReleaseIntArrayElements(configArray, config, 0);

    return configArray;
}

// midi write
jboolean
Java_org_billthefarmer_melodeon_MidiDriver_write(JNIEnv *env,
						  jobject obj,
						  jbyteArray byteArray)
{
    jboolean isCopy;
    EAS_RESULT result;
    jint length;
    EAS_U8 *buf;

    if (pEASData == NULL || midiHandle == NULL)
	return JNI_FALSE;

    buf = (EAS_U8 *)env->GetByteArrayElements(byteArray, &isCopy);
    length = env->GetArrayLength(byteArray);

    // lock
    pthread_mutex_lock(&mutex);

    result = pEAS_WriteMIDIStream(pEASData, midiHandle, buf, length);

    // unlock
    pthread_mutex_unlock(&mutex);      

    env->ReleaseByteArrayElements(byteArray, (jbyte *)buf, 0);

    if (result != EAS_SUCCESS)
	return JNI_FALSE;

    return JNI_TRUE;
}

// shutdown EAS midi
jboolean
Java_org_billthefarmer_melodeon_MidiDriver_shutdown(JNIEnv *env,
						     jobject obj)
{
    shutdownAudio();

    if (buffer != NULL)
	free(buffer);
    buffer = NULL;

    shutdownEAS();

    return JNI_TRUE;
}

extern "C" {
    jint JNI_OnLoad(JavaVM* vm, void* reserved);
}

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env;

    if (vm->GetEnv((void**)(&env), JNI_VERSION_1_6) != JNI_OK)
    {
        return -1;
    }

    jclass linkageErrorClass =
	env->FindClass("java/lang/LinkageError");
    if (linkageErrorClass == NULL)
    {
        LOG_E(LOG_TAG, "Failed to resolve java/lang/LinkageError");
        return -1;
    }

    void *libHandler;

    LOG_I(LOG_TAG,
			"Init function called");

    libHandler = dlopen("libsonivox.so", RTLD_LAZY);
    if (!libHandler)
    {
        env->ThrowNew(linkageErrorClass, "dlopen libsonivox.so failed");
        return -1;
    }

    else
    {
        LOG_I(LOG_TAG, "dlopen libsonivox.so passed" );
    }

    pEAS_Config = (EAS_PUBLIC const S_EAS_LIB_CONFIG *(*) (void))
	dlsym(libHandler, "EAS_Config");
    if (!pEAS_Config)
    {
        env->ThrowNew(linkageErrorClass, "EAS_Config resolution failed");
        return -1;
    }
    
    pEAS_Init = (EAS_PUBLIC EAS_RESULT (*) (EAS_DATA_HANDLE *ppEASData))
	dlsym(libHandler, "EAS_Init");
    if (!pEAS_Config)
    {
        env->ThrowNew(linkageErrorClass, "EAS_Init resolution failed");
        return -1;
    }
      
    pEAS_SetParameter = 
        (EAS_PUBLIC EAS_RESULT (*) (EAS_DATA_HANDLE pEASData,
				    EAS_I32 module,
				    EAS_I32 param,
				    EAS_I32 value))
        dlsym(libHandler, "EAS_SetParameter");
    if (!pEAS_SetParameter)
    {
        env->ThrowNew(linkageErrorClass, "EAS_SetParameter resolution failed");
        return -1;
    }
    
    pEAS_OpenMIDIStream = 
        (EAS_PUBLIC EAS_RESULT (*) (EAS_DATA_HANDLE pEASData,
				    EAS_HANDLE *pStreamHandle,
				    EAS_HANDLE streamHandle))
        dlsym(libHandler, "EAS_OpenMIDIStream");
    if (!pEAS_OpenMIDIStream)
    {
        env->ThrowNew(linkageErrorClass,
		      "EAS_OpenMIDIStream resolution failed");
        return -1;
    }

    pEAS_Shutdown = (EAS_PUBLIC EAS_RESULT (*) (EAS_DATA_HANDLE pEASData))
	dlsym(libHandler, "EAS_Shutdown");
    if (!pEAS_Shutdown) {
        env->ThrowNew(linkageErrorClass, "EAS_Shutdown resolution failed");
        return -1;
    }

    pEAS_Render =
        (EAS_PUBLIC EAS_RESULT (*) (EAS_DATA_HANDLE pEASData,
				    EAS_PCM *pOut,
				    EAS_I32 numRequested,
                                    EAS_I32 *pNumGenerated))
        dlsym(libHandler, "EAS_Render");
    if (!pEAS_Render)
    {
        env->ThrowNew(linkageErrorClass, "EAS_Render resolution failed");
        return -1;
    }
    
    pEAS_WriteMIDIStream = (EAS_PUBLIC EAS_RESULT (*)(EAS_DATA_HANDLE pEASData,
						      EAS_HANDLE streamHandle,
						      EAS_U8 *pBuffer,
                                                      EAS_I32 count))
        dlsym(libHandler, "EAS_WriteMIDIStream");
    if (!pEAS_WriteMIDIStream)
    {
        env->ThrowNew(linkageErrorClass,
		      "EAS_WriteMIDIStream resolution failed");
        return -1;
    }

    pEAS_CloseMIDIStream = (EAS_PUBLIC EAS_RESULT (*) (EAS_DATA_HANDLE pEASData,
						       EAS_HANDLE streamHandle))
        dlsym(libHandler, "EAS_CloseMIDIStream");
    if (!pEAS_CloseMIDIStream
	) {
        env->ThrowNew(linkageErrorClass,
		      "EAS_CloseMIDIStream resolution failed");
        return -1;
    }

    LOG_I(LOG_TAG, "Init function passed");

    return JNI_VERSION_1_6;
}
